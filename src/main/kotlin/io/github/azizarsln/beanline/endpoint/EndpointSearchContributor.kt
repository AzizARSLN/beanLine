package io.github.azizarsln.beanline.endpoint

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import javax.swing.JList
import javax.swing.ListCellRenderer

const val ENDPOINT_CONTRIBUTOR_ID = "Beanline.Endpoints"

class EndpointSearchContributor(private val project: Project) : SearchEverywhereContributor<Endpoint> {

    override fun getSearchProviderId(): String = ENDPOINT_CONTRIBUTOR_ID

    override fun getGroupName(): String = "Endpoints"

    override fun getSortWeight(): Int = 500

    override fun showInFindResults(): Boolean = false

    override fun isShownInSeparateTab(): Boolean = true

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in Endpoint>,
    ) {
        val matches = ReadAction.nonBlocking<List<Endpoint>> {
            val query = UrlQuery.parse(pattern)
            EndpointIndex.getInstance(project).all()
                .mapNotNull { endpoint ->
                    matchScore(query, endpoint.verbs, endpoint.template, endpoint.displayName)?.let { endpoint to it }
                }
                .sortedWith(compareByDescending<Pair<Endpoint, Int>> { it.second }.thenBy { it.first.template })
                .map { it.first }
        }.wrapProgress(progressIndicator).executeSynchronously()

        for (endpoint in matches) {
            if (!consumer.process(endpoint)) return
        }
    }

    override fun processSelectedItem(selected: Endpoint, modifiers: Int, searchText: String): Boolean {
        selected.method.element?.navigate(true)
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in Endpoint> =
        object : ColoredListCellRenderer<Endpoint>() {
            override fun customizeCellRenderer(
                list: JList<out Endpoint>,
                value: Endpoint,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = AllIcons.Nodes.Method
                append(value.verbs.joinToString(",") + " ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(value.template)
                append("  " + value.displayName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
}

class EndpointSearchContributorFactory : SearchEverywhereContributorFactory<Endpoint> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<Endpoint> =
        EndpointSearchContributor(requireNotNull(initEvent.getData(CommonDataKeys.PROJECT)))
}
