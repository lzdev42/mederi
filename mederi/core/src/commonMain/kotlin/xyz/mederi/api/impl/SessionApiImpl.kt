package xyz.mederi.api.impl

import kotlinx.coroutines.flow.Flow
import xyz.mederi.api.CreateSessionRequest
import xyz.mederi.api.RawMessageDto
import xyz.mederi.api.RenameSessionRequest
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.api.SessionApi
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.domain.model.FileDiff
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.Session
import xyz.mederi.session.SessionManager

/**
 * SessionApi 实现。
 */
class SessionApiImpl(private val sessionManager: SessionManager) : SessionApi {

    override suspend fun list(): List<Session> = mederiCall { sessionManager.list() }

    override suspend fun create(request: CreateSessionRequest): Session = mederiCall {
        sessionManager.create(
            agentConfig = request.agentConfig,
            projectId = request.projectId,
            title = request.title,
            env = request.env
        )
    }

    override suspend fun get(id: String): Session = mederiCall { sessionManager.require(id) }

    override suspend fun rename(id: String, request: RenameSessionRequest): Session = mederiCall {
        sessionManager.rename(id, request.title)
    }

    override suspend fun delete(id: String) {
        mederiCall { sessionManager.delete(id) }
    }

    override suspend fun abort(id: String) {
        mederiCall { sessionManager.abort(id) }
    }

    override suspend fun sendMessage(sessionId: String, request: SendMessageRequest) = mederiCall {
        sessionManager.sendMessage(sessionId, request)
    }

    override suspend fun rollbackToMessage(sessionId: String, messageId: String) = mederiCall {
        sessionManager.rollbackToMessage(sessionId, messageId)
    }

    override suspend fun resolveQuestion(sessionId: String, questionId: String, answers: List<List<String>>) = mederiCall {
        sessionManager.resolveQuestion(sessionId, questionId, answers)
    }

    override suspend fun resolvePlanApproval(sessionId: String, planId: String, approved: Boolean) = mederiCall {
        sessionManager.resolvePlanApproval(sessionId, planId, approved)
    }

    override suspend fun compressHistory(sessionId: String) = mederiCall {
        sessionManager.compressHistory(sessionId)
    }

    override suspend fun listMessages(sessionId: String): List<Message> = mederiCall {
        sessionManager.listMessages(sessionId)
    }

    override suspend fun getMessage(sessionId: String, messageId: String): Message = mederiCall {
        sessionManager.getMessage(sessionId, messageId)
    }

    override suspend fun listRawMessages(sessionId: String): List<RawMessageDto> = mederiCall {
        sessionManager.listRawMessages(sessionId).map {
            RawMessageDto(
                seq = it.seq,
                messageId = it.messageId,
                role = it.role,
                payload = it.payload,
                createdAt = it.createdAt
            )
        }
    }

    override suspend fun getFileDiffs(sessionId: String, messageId: String?): List<FileDiff> = mederiCall {
        sessionManager.getFileDiffs(sessionId, messageId)
    }

    override fun events(sessionId: String): Flow<MederiEvent> =
        sessionManager.events(sessionId)

    override fun events(): Flow<MederiEvent> = sessionManager.events()
}
