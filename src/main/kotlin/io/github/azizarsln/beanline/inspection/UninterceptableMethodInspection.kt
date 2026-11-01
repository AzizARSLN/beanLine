package io.github.azizarsln.beanline.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

private val BLOCKING_MODIFIERS = listOf(PsiModifier.PRIVATE, PsiModifier.FINAL, PsiModifier.STATIC)

class UninterceptableMethodInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                val annotation = method.findOwnAnnotation(INTERCEPTION_ANNOTATIONS) ?: return
                val modifier = BLOCKING_MODIFIERS.firstOrNull(method::hasModifierProperty) ?: return
                val fixes = if (modifier == PsiModifier.STATIC) emptyArray() else arrayOf<LocalQuickFix>(MakeInterceptableFix())
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    "@${annotation.shortName} may be ignored because a container proxy cannot intercept a $modifier method",
                    *fixes,
                )
            }
        }

    private class MakeInterceptableFix : LocalQuickFix {
        override fun getFamilyName(): String = "Make method public and non-final"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val method = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMethod::class.java, false) ?: return
            PsiUtil.setModifierProperty(method, PsiModifier.FINAL, false)
            if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
                PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true)
            }
        }
    }
}
