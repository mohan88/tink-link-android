package com.tink.link.core.credentials

import com.tink.link.coroutines.launchForResult
import com.tink.model.credentials.Credentials
import com.tink.model.credentials.RefreshableItem
import com.tink.model.misc.Field
import com.tink.model.provider.Provider
import com.tink.service.credentials.CredentialsAuthenticateDescriptor
import com.tink.service.credentials.CredentialsCreationDescriptor
import com.tink.service.credentials.CredentialsRefreshDescriptor
import com.tink.service.credentials.CredentialsService
import com.tink.service.credentials.CredentialsUpdateDescriptor
import com.tink.service.handler.ResultHandler
import com.tink.service.network.TinkConfiguration
import com.tink.service.streaming.publisher.Stream
import com.tink.service.streaming.publisher.StreamObserver
import com.tink.service.streaming.publisher.StreamSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/**
 * Repository for creating, accessing and modifying credentials.
 *
 * @constructor Create a new repository instance from a [CredentialsService]
 * This is usually done inside the Tink framework and it should normally not be necessary to create your own instance.
 */
class CredentialsRepository @Inject constructor(
    private val service: CredentialsService,
    private val tinkConfiguration: TinkConfiguration
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Returns a [Stream] containing the list of [Credentials] objects.
     *
     * You can subscribe to the [Stream] and observe changes in the [Credentials] objects and act upon them.
     */
    fun listStream(): Stream<List<Credentials>> {
        return service.list()
    }

    /**
     * Creates a new [Credentials] object. If the user fails to authenticate the [Credentials]
     * (for example writing the wrong password) a [delete] will be triggered.
     * Note that the delete is performed asynchronously and is thus not guaranteed to have completed
     * when the [statusChangeObserver] calls [onError][StreamObserver.onError].
     *
     * @param providerName Identifier for the [Provider]. See [Provider.name]
     * @param credentialsType The [Credentials.Type] used to authenticate the user to the financial institution
     * @param fields The map of [Field] name and value pairs for the [Provider]
     * @param statusChangeObserver An observer which will receive callbacks when there are
     * updates to the status of the credentials. Successful and intermediate status will be posted in
     * [onNext][StreamObserver.onNext], whereas failures and errors will be passed as [Throwable]
     * via [onError][StreamObserver.onError]. If the creation finished successfully, you will also
     * receive a call to [onCompleted][StreamObserver.onCompleted], after which there will be no other
     * calls to this stream observer.
     * @param items A list of [RefreshableItem] representing the data types to aggregate from the provider. If omitted, all data types are aggregated.
     */
    fun create(
        providerName: String,
        credentialsType: Credentials.Type,
        fields: Map<String, String>,
        statusChangeObserver: StreamObserver<CredentialsStatus>,
        items: Set<RefreshableItem>? = null
    ): StreamSubscription {
        return CreateCredentialsTask(
            CredentialsCreationDescriptor(
                providerName,
                credentialsType,
                fields,
                tinkConfiguration.redirectUri,
                items
            ),
            service,
            statusChangeObserver
        )
    }

    /**
     * Updates the [Credentials] matching the id.
     *
     * You can only update the value of a [Field] that is mutable.
     *
     * @param credentialsId Identifier for the [Credentials] that is being updated
     * @param fields The map of [Field] name and value pairs for the [Credentials]
     * @param statusChangeObserver An observer which will receive callbacks when there are
     * updates to the status of the credentials. Successful and intermediate status will be posted in
     * [onNext][StreamObserver.onNext], whereas failures and errors will be passed as [Throwable]
     * via [onError][StreamObserver.onError]. If the creation finished successfully, you will also
     * receive a call to [onCompleted][StreamObserver.onCompleted], after which there will be no other
     * calls to this stream observer.
     */
    fun update(
        credentialsId: String,
        providerName: String,
        fields: Map<String, String>,
        statusChangeObserver: StreamObserver<CredentialsStatus>
    ): StreamSubscription {
        return UpdateCredentialsTask(
            CredentialsUpdateDescriptor(
                id = credentialsId,
                providerName = providerName,
                fields = fields,
                appUri = tinkConfiguration.redirectUri
            ),
            service,
            statusChangeObserver
        )
    }

    /**
     * Refreshes all [Credentials] objects matching the list of ids.
     *
     * @param credentialsId Id of the [Credentials] that are being refreshed
     * @param resultHandler The [ResultHandler] for processing error and success callbacks
     * @param items A list of [RefreshableItem] representing the data types to aggregate from the Provider. If omitted, all data types are aggregated.
     */
    fun refresh(
        credentialsId: String,
        resultHandler: ResultHandler<Unit>,
        items: Set<RefreshableItem>? = null
    ) {
        scope.launchForResult(resultHandler) {
            service.refresh(CredentialsRefreshDescriptor(credentialsId, items))
        }
    }

    /**
     * Deletes the [Credentials] matching the id.
     *
     * @param credentialsId Identifier for the [Credentials] that is being deleted
     * @param resultHandler The [ResultHandler] for processing error and success callbacks
     */
    fun delete(credentialsId: String, resultHandler: ResultHandler<Unit>) {
        scope.launchForResult(resultHandler) {
            service.delete(credentialsId)
        }
    }

    /**
     * Manually authenticates the [Credentials] matching the id. This is only applicable for PSD2 credentials.
     *
     * @param credentialsId Identifier for the [Credentials] that is being authenticated
     * @param statusChangeObserver An observer which will receive callbacks when there are
     * updates to the status of the credentials. Successful and intermediate status will be posted in
     * [onNext][StreamObserver.onNext], whereas failures and errors will be passed as [Throwable]
     * via [onError][StreamObserver.onError]. If the creation finished successfully, you will also
     * receive a call to [onCompleted][StreamObserver.onCompleted], after which there will be no other
     * calls to this stream observer.
     */
    fun authenticate(
        credentialsId: String,
        statusChangeObserver: StreamObserver<CredentialsStatus>
    ): StreamSubscription {
        return AuthenticateCredentialsTask(
            descriptor = CredentialsAuthenticateDescriptor(
                id = credentialsId,
                appUri = tinkConfiguration.redirectUri
            ),
            credentialsService = service,
            streamObserver = statusChangeObserver
        )
    }

    private fun enable(credentialsId: String, resultHandler: ResultHandler<Unit>) {
        scope.launchForResult(resultHandler) {
            service.enable(credentialsId)
        }
    }

    private fun disable(credentialsId: String, resultHandler: ResultHandler<Unit>) {
        scope.launchForResult(resultHandler) {
            service.disable(credentialsId)
        }
    }

    /**
     * Submits the supplemental information required to authenticate the [Credentials] matching the id.
     *
     * @param credentialsId Identifier for the [Credentials] that is being authenticated
     * @param information The map of [Field] name and value pairs representing the supplemental information to be sent
     * @param handler The [ResultHandler] for processing error and success callbacks
     */
    fun supplementInformation(
        credentialsId: String,
        information: Map<String, String>,
        handler: ResultHandler<Unit>
    ) {
        scope.launchForResult(handler) {
            service.supplementInformation(credentialsId, information)
        }
    }

    /**
     * Cancels the supplemental information submission flow.
     * The backend service is informed to stop waiting for the supplemental information.
     * This method should be called if you want to dismiss the supplemental information flow.
     *
     * @param credentialsId Identifier for the [Credentials] that is being authenticated
     * @param handler The [ResultHandler] for processing error and success callbacks
     */
    fun cancelSupplementalInformation(credentialsId: String, handler: ResultHandler<Unit>) {
        scope.launchForResult(handler) {
            service.cancelSupplementalInformation(credentialsId)
        }
    }

    /**
     * Sends the third party callback information from an Account Servicing Payment Service Provider (ASPSP).
     *
     * This is usually done inside the TinkLink framework and it should normally not be necessary to do this on your own.
     *
     * @param state The value corresponding to the `state` key from the received callback parameters.
     * @param parameters The map of key and value pairs, other than `state` from the received callback parameters.
     * @param handler The [ResultHandler] for processing error and success callbacks
     */
    fun thirdPartyCallback(
        state: String,
        parameters: Map<String, String>,
        handler: ResultHandler<Unit>
    ) {
        scope.launchForResult(handler) {
            service.thirdPartyCallback(state, parameters)
        }
    }
}
