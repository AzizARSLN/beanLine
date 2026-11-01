package io.github.azizarsln.beanline.bean

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.PsiClassType

private fun bothNamespaces(vararg names: String): List<String> =
    names.flatMap { listOf("javax.$it", "jakarta.$it") }

private val MANAGED_BEAN_ANNOTATIONS: List<String> =
    bothNamespaces(
        "ejb.Stateless", "ejb.Stateful", "ejb.Singleton", "ejb.MessageDriven",
        "enterprise.context.ApplicationScoped", "enterprise.context.SessionScoped",
        "enterprise.context.RequestScoped", "enterprise.context.ConversationScoped",
        "enterprise.context.Dependent", "faces.view.ViewScoped",
        "inject.Named", "inject.Singleton",
    ) + listOf(
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.context.annotation.Configuration",
    )

private val INJECTION_ANNOTATIONS: List<String> =
    bothNamespaces("ejb.EJB", "inject.Inject", "annotation.Resource") +
        "org.springframework.beans.factory.annotation.Autowired"

const val SPRING_BEAN_FACTORY_METHOD = "org.springframework.context.annotation.Bean"

fun PsiModifierListOwner.hasAnyAnnotation(names: Collection<String>): Boolean = names.any(::hasAnnotation)

fun PsiClass.isManagedBean(): Boolean =
    !isInterface && !isAnnotationType && !isEnum && hasAnyAnnotation(MANAGED_BEAN_ANNOTATIONS)

fun PsiModifierListOwner.hasInjectionAnnotation(): Boolean = hasAnyAnnotation(INJECTION_ANNOTATIONS)

fun PsiField.isInjected(): Boolean = hasInjectionAnnotation()

/** Fields, constructor parameters and setter parameters in [scope] whose type can receive [bean]. */
fun findInjectionPoints(bean: PsiClass, scope: GlobalSearchScope): List<PsiElement> {
    val types = listOf(bean) + bean.interfaces.filterNot { it.qualifiedName.orEmpty().startsWith("java.") }
    return types
        .flatMap { type -> ReferencesSearch.search(type, scope).findAll() }
        .mapNotNull { reference -> injectionPointOf(reference.element) }
        .distinct()
}

private fun injectionPointOf(usage: PsiElement): PsiElement? {
    val owner = PsiTreeUtil.getParentOfType(usage, PsiField::class.java, PsiParameter::class.java) ?: return null
    return when (owner) {
        is PsiField -> owner.takeIf {
            it.isInjected() && PsiTreeUtil.isAncestor(it.typeElement, usage, false)
        }
        is PsiParameter -> owner.takeIf {
            PsiTreeUtil.isAncestor(it.typeElement, usage, false) && isInjectedParameter(it)
        }
        else -> null
    }
}

private fun isInjectedParameter(parameter: PsiParameter): Boolean {
    val method = parameter.declarationScope as? PsiMethod ?: return false
    if (method.hasInjectionAnnotation()) return true
    val owner = method.containingClass ?: return false
    return method.isConstructor && owner.constructors.size == 1 && owner.isManagedBean()
}

/** Concrete beans that can satisfy an injection point of type [type], unwrapping `Provider<T>`, `List<T>` and similar. */
fun findCandidateBeans(type: PsiType, scope: GlobalSearchScope): List<PsiClass> {
    val direct = PsiUtil.resolveClassInClassTypeOnly(type) ?: return emptyList()
    val beans = concreteBeans(direct, scope)
    if (beans.isNotEmpty()) return beans
    val argument = (type as? PsiClassType)?.parameters?.singleOrNull() ?: return emptyList()
    val wrapped = PsiUtil.resolveClassInClassTypeOnly(argument) ?: return emptyList()
    return concreteBeans(wrapped, scope)
}

private fun concreteBeans(type: PsiClass, scope: GlobalSearchScope): List<PsiClass> =
    (listOf(type) + ClassInheritorsSearch.search(type, scope, true).findAll())
        .filter { it.isManagedBean() && !it.hasModifierProperty("abstract") }
        .distinct()
