package io.github.azizarsln.beanline.commit

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

class CommitMessageCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        object : CheckinHandler() {
            override fun beforeCheckin(): ReturnResult {
                if (panel.commitMessage.isBlank()) {
                    CommitMessageGenerator.generate(panel.project, panel.selectedChanges)
                        ?.let { panel.commitMessage = it }
                }
                return ReturnResult.COMMIT
            }
        }
}
