package io.github.azizarsln.beanline.bean

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.search.GlobalSearchScope

private val BEAN_ICON = IconLoader.getIcon("/icons/bean.svg", BeanLineMarkerProvider::class.java)

class BeanLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        if (element !is PsiIdentifier) return
        when (val parent = element.parent) {
            is PsiClass -> if (parent.nameIdentifier == element && parent.isManagedBean()) {
                result += marker(element, "Injected into", "Injection Points", "No injection points found") {
                    findInjectionPoints(parent, GlobalSearchScope.projectScope(parent.project))
                }
            }
            is PsiField -> if (parent.nameIdentifier == element && parent.isInjected()) {
                result += marker(element, "Provided by", "Candidate Beans", "No candidate beans found") {
                    findCandidateBeans(parent.type, GlobalSearchScope.projectScope(parent.project))
                }
            }
        }
    }

    private fun marker(
        element: PsiElement,
        tooltip: String,
        title: String,
        emptyText: String,
        targets: () -> Collection<PsiElement>,
    ): RelatedItemLineMarkerInfo<*> =
        NavigationGutterIconBuilder.create(BEAN_ICON)
            .setTargets(NotNullLazyValue.lazy(targets))
            .setTooltipText(tooltip)
            .setPopupTitle(title)
            .setEmptyPopupText(emptyText)
            .createLineMarkerInfo(element)
}
