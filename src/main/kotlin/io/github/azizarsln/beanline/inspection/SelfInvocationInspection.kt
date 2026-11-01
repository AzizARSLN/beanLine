package io.github.azizarsln.beanline.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.util.PsiTreeUtil
import io.github.azizarsln.beanline.bean.isManagedBean

class SelfInvocationInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                val qualifier = call.methodExpression.qualifierExpression
                if (qualifier != null && qualifier !is PsiThisExpression) return

                val callee = call.resolveMethod() ?: return
                if (callee.isConstructor || callee.hasModifierProperty(PsiModifier.STATIC)) return

                val bean = enclosingNamedClass(call) ?: return
                if (!bean.isManagedBean()) return
                val calleeOwner = callee.containingClass ?: return
                if (calleeOwner != bean && !bean.isInheritor(calleeOwner, true)) return

                val caller = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java)
                if (caller == callee) return

                val ignored = ignoredAnnotation(callee, caller, bean) ?: return
                holder.registerProblem(
                    call.methodExpression.referenceNameElement ?: call,
                    "@${ignored.shortName} on '${callee.name}' does not apply to a call from the same bean, " +
                        "because the call does not pass through the container proxy",
                )
            }
        }

    private fun enclosingNamedClass(call: PsiMethodCallExpression): PsiClass? {
        var current = PsiTreeUtil.getParentOfType(call, PsiClass::class.java, true, PsiLambdaExpression::class.java)
            ?: PsiTreeUtil.getParentOfType(call, PsiClass::class.java)
        while (current is PsiAnonymousClass) {
            current = PsiTreeUtil.getParentOfType(current, PsiClass::class.java)
        }
        return current
    }

    private fun ignoredAnnotation(callee: PsiMethod, caller: PsiMethod?, bean: PsiClass): PsiAnnotation? {
        callee.findOwnAnnotation(BEHAVIOR_ANNOTATIONS)?.let { return it }
        val transaction = callee.findOwnAnnotation(TRANSACTION_ANNOTATIONS) ?: return null
        val callerTransaction = caller?.findOwnAnnotation(TRANSACTION_ANNOTATIONS)
            ?: TRANSACTION_ANNOTATIONS.firstNotNullOfOrNull(bean::getAnnotation)
        return transaction.takeUnless { hasSameSettings(it, callerTransaction) }
    }

    private fun hasSameSettings(callee: PsiAnnotation, caller: PsiAnnotation?): Boolean {
        val settings = settingsOf(callee)
        if (caller == null) {
            // An EJB method without an annotation runs with REQUIRED by default.
            return callee.qualifiedName in EJB_TRANSACTION_ATTRIBUTES && settings in EJB_DEFAULT_SETTINGS
        }
        return settings == settingsOf(caller)
    }

    private fun settingsOf(annotation: PsiAnnotation): String =
        annotation.parameterList.text.orEmpty()
            .filterNot(Char::isWhitespace)
            .replace("TransactionAttributeType.", "")
            .replace("Transactional.TxType.", "")
            .replace("TxType.", "")
            .replace("Propagation.", "")
}

private val EJB_DEFAULT_SETTINGS = setOf("", "()", "(REQUIRED)")
