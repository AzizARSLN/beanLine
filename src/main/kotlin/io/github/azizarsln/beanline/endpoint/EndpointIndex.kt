package io.github.azizarsln.beanline.endpoint

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

class Endpoint(
    val verbs: List<String>,
    val template: String,
    val displayName: String,
    val method: SmartPsiElementPointer<PsiMethod>,
)

private val NAMESPACES = listOf("javax", "jakarta")
private val JAX_RS_VERBS = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
private val JAX_RS_PATHS = NAMESPACES.map { "$it.ws.rs.Path" }
private val JAX_RS_VERB_ANNOTATIONS: Map<String, String> =
    NAMESPACES.flatMap { ns -> JAX_RS_VERBS.map { "$ns.ws.rs.$it" to it } }.toMap()

private const val SPRING_WEB = "org.springframework.web.bind.annotation"
private const val SPRING_REQUEST_MAPPING = "$SPRING_WEB.RequestMapping"
private val SPRING_CONTROLLERS = listOf("$SPRING_WEB.RestController", "org.springframework.stereotype.Controller")
private val SPRING_VERB_MAPPINGS = mapOf(
    "$SPRING_WEB.GetMapping" to "GET",
    "$SPRING_WEB.PostMapping" to "POST",
    "$SPRING_WEB.PutMapping" to "PUT",
    "$SPRING_WEB.DeleteMapping" to "DELETE",
    "$SPRING_WEB.PatchMapping" to "PATCH",
)

@Service(Service.Level.PROJECT)
class EndpointIndex(private val project: Project) {

    /** All endpoints in project sources. Call inside a read action. Empty while indexes are being built. */
    fun all(): List<Endpoint> {
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                scan(),
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }
    }

    private fun scan(): List<Endpoint> {
        val jaxRs = annotatedClasses(JAX_RS_PATHS).flatMap(::jaxRsEndpoints)
        val spring = annotatedClasses(SPRING_CONTROLLERS).flatMap(::springEndpoints)
        return jaxRs + spring
    }

    private fun annotatedClasses(annotations: List<String>): List<PsiClass> {
        val facade = JavaPsiFacade.getInstance(project)
        val libraries = GlobalSearchScope.allScope(project)
        val sources = GlobalSearchScope.projectScope(project)
        return annotations
            .mapNotNull { facade.findClass(it, libraries) }
            .flatMap { AnnotatedElementsSearch.searchPsiClasses(it, sources).findAll() }
            .filterNot { it.isAnnotationType }
            .distinct()
    }

    private fun jaxRsEndpoints(owner: PsiClass): List<Endpoint> {
        val prefix = JAX_RS_PATHS.firstNotNullOfOrNull(owner::getAnnotation)?.let { stringValues(it, "value") }.orEmpty()
        return inheritedMethods(owner).flatMap { method ->
            val verb = JAX_RS_VERB_ANNOTATIONS.entries
                .firstOrNull { (annotation, _) -> findInHierarchy(method, annotation) != null }
                ?.value ?: return@flatMap emptyList()
            val path = JAX_RS_PATHS.firstNotNullOfOrNull { findInHierarchy(method, it) }
                ?.let { stringValues(it, "value") }.orEmpty()
            endpoints(owner, method, listOf(verb), prefix, path)
        }
    }

    private fun springEndpoints(owner: PsiClass): List<Endpoint> {
        val prefix = owner.getAnnotation(SPRING_REQUEST_MAPPING)?.let { stringValues(it, "value", "path") }.orEmpty()
        return inheritedMethods(owner).flatMap { method ->
            val shortcut = SPRING_VERB_MAPPINGS.entries.firstNotNullOfOrNull { (annotation, verb) ->
                findInHierarchy(method, annotation)?.let { it to listOf(verb) }
            }
            val (mapping, verbs) = shortcut
                ?: findInHierarchy(method, SPRING_REQUEST_MAPPING)?.let { it to requestMappingVerbs(it) }
                ?: return@flatMap emptyList()
            endpoints(owner, method, verbs, prefix, stringValues(mapping, "value", "path"))
        }
    }

    private fun requestMappingVerbs(mapping: PsiAnnotation): List<String> {
        val declared = mapping.findDeclaredAttributeValue("method") ?: return listOf(ANY_VERB)
        return AnnotationUtil.arrayAttributeValues(declared).map { it.text.substringAfterLast('.') }
    }

    private fun endpoints(
        owner: PsiClass,
        method: PsiMethod,
        verbs: List<String>,
        prefixes: List<String>,
        paths: List<String>,
    ): List<Endpoint> {
        val pointer = SmartPointerManager.createPointer(method)
        val name = "${owner.name}#${method.name}"
        return prefixes.ifEmpty { listOf("") }.flatMap { prefix ->
            paths.ifEmpty { listOf("") }.map { path ->
                Endpoint(verbs, joinPaths(prefix, path), name, pointer)
            }
        }
    }

    private fun inheritedMethods(owner: PsiClass): List<PsiMethod> =
        owner.visibleSignatures.map { it.method }.filterNot { it.isConstructor }

    private fun findInHierarchy(method: PsiMethod, annotation: String): PsiAnnotation? =
        AnnotationUtil.findAnnotationInHierarchy(method, setOf(annotation))

    private fun stringValues(annotation: PsiAnnotation, vararg attributes: String): List<String> {
        val helper = JavaPsiFacade.getInstance(project).constantEvaluationHelper
        val value = attributes.firstNotNullOfOrNull(annotation::findDeclaredAttributeValue) ?: return emptyList()
        return AnnotationUtil.arrayAttributeValues(value)
            .mapNotNull { helper.computeConstantExpression(it) as? String }
    }

    companion object {
        fun getInstance(project: Project): EndpointIndex = project.service()
    }
}

fun joinPaths(prefix: String, path: String): String =
    "/" + (splitPath(prefix) + splitPath(path)).joinToString("/")
