package io.github.azizarsln.beanline.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil
import io.github.azizarsln.beanline.bean.SPRING_BEAN_FACTORY_METHOD
import io.github.azizarsln.beanline.bean.isInjected
import io.github.azizarsln.beanline.bean.isManagedBean

class ManagedBeanNewInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile
        if (file != null && TestSourcesFilter.isTestSources(file, holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        return object : JavaElementVisitor() {
            override fun visitNewExpression(expression: PsiNewExpression) {
                if (expression.anonymousClass != null) return
                val reference = expression.classReference ?: return
                val bean = reference.resolve() as? PsiClass ?: return
                if (!bean.isManagedBean()) return
                val injected = bean.allFields.firstOrNull { it.isInjected() } ?: return
                val method = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                if (method?.hasAnnotation(SPRING_BEAN_FACTORY_METHOD) == true) return
                holder.registerProblem(
                    reference,
                    "'${bean.name}' is a managed bean. Created with new, its injected field '${injected.name}' stays null",
                )
            }
        }
    }
}
