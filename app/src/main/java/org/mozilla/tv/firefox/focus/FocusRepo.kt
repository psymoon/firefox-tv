/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.focus

import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.VisibleForTesting
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import org.mozilla.tv.firefox.R
import org.mozilla.tv.firefox.ScreenController
import org.mozilla.tv.firefox.ScreenControllerStateMachine
import org.mozilla.tv.firefox.ScreenControllerStateMachine.ActiveScreen
import org.mozilla.tv.firefox.ext.itAndAncestorsAreVisible
import org.mozilla.tv.firefox.pinnedtile.PinnedTileRepo
import org.mozilla.tv.firefox.pocket.PocketVideoRepo
import org.mozilla.tv.firefox.session.SessionRepo
import org.mozilla.tv.firefox.utils.URLs

private const val INVALID_VIEW_ID = -1

// TODO ↓ SEVERIN ↓

class FocusRequest(private vararg val ids: Int) {
    fun requestOnFirstEnabled(root: View) {
        ids.toList()
                .firstAvailableViewIfAny(root)
                ?.requestFocus()
    }
}
private val NO_FOCUS_REQUEST = FocusRequest()

class FocusNode(
        val viewId: Int,
        private val nextFocusUpIds: List<Int>? = null,
        private val nextFocusDownIds: List<Int>? = null,
        private val nextFocusLeftIds: List<Int>? = null,
        private val nextFocusRightIds: List<Int>? = null
) {
    fun updateViewNodeTree(focusedView: View) {
        assert(focusedView.id == viewId) // todo assert or require?
        val root = focusedView.rootView

        fun List<Int>?.applyFirstAvailableIdToView(root: View, action: View.(Int) -> Unit) {
            this?: return
            val firstAvailable = this.firstAvailableViewIfAny(root) ?: return
            focusedView.action(firstAvailable.id)
        }

        nextFocusUpIds.applyFirstAvailableIdToView(root) { nextFocusUpId = it }
        nextFocusDownIds.applyFirstAvailableIdToView(root) { nextFocusDownId = it }
        nextFocusLeftIds.applyFirstAvailableIdToView(root) { nextFocusLeftId = it }
        nextFocusRightIds.applyFirstAvailableIdToView(root) { nextFocusRightId = it }
    }
}

private val invalidIds = listOf(
        -1,
        R.id.nested_web_view
)

// Sequence prevents unnecessary findViewById calls
private fun List<Int>.firstAvailableViewIfAny(root: View): View? {
    return this.asSequence()
            .mapNotNull { root.findViewById<View>(it) }
            .filter { it.isEnabled && it.itAndAncestorsAreVisible(3) }
            .firstOrNull()
}

class FocusRepo(
    screenController: ScreenController,
    sessionRepo: SessionRepo,
    pinnedTileRepo: PinnedTileRepo,
    pocketRepo: PocketVideoRepo
) : ViewTreeObserver.OnGlobalFocusChangeListener {

    private val focusChanged = PublishSubject.create<Pair<Int, Int>>()

    // Edge case, url bar is unaffected on startup (works if it regains focus)
    val focusNodeForCurrentlyFocusedView: Observable<FocusNode> = focusChanged
            .map { oldAndNewFocus -> oldAndNewFocus.second }
            .map { id ->
                when (id) {
                    R.id.navUrlInput -> getUrlBarFocusNode()
                    R.id.pocketVideoMegaTileView -> getPocketMegatileFocusNode()
                    else -> FocusNode(viewId = id)
                }
            }

    val focusRequests: Observable<FocusRequest> by lazy {
        Observable.merge(
                defaultViewAfterScreenChange,
                requestAfterFocusLost
        )
    }

    private fun getUrlBarFocusNode() = FocusNode(
            viewId = R.id.navUrlInput,
            nextFocusUpIds = listOf(R.id.navButtonBack, R.id.navButtonForward, R.id.navButtonReload, R.id.turboButton),
            nextFocusDownIds = listOf(R.id.pocketVideoMegaTileView, R.id.megaTileTryAgainButton, R.id.tileContainer, R.id.navUrlInput)
    )

    private fun getPocketMegatileFocusNode() = FocusNode(
            viewId = R.id.pocketVideoMegaTileView,
            nextFocusDownIds = listOf(R.id.tileContainer, R.id.settingsTileContainer)
    )

    private val defaultViewAfterScreenChange: Observable<FocusRequest> = screenController.currentActiveScreen
            .startWith(ActiveScreen.NAVIGATION_OVERLAY)
            .buffer(2, 1) // This emits a list of the previous and current screen. See RxTest.kt
            .map { (previousScreen, currentScreen) ->
                when (currentScreen) {
                    ActiveScreen.NAVIGATION_OVERLAY -> {
                        when (previousScreen) {
                            ActiveScreen.WEB_RENDER -> FocusRequest(R.id.navButtonBack, R.id.navButtonForward, R.id.navButtonReload, R.id.navUrlInput)
                            ActiveScreen.POCKET -> FocusRequest(R.id.pocketVideoMegaTileView)
                            ActiveScreen.SETTINGS -> FocusRequest(R.id.settings_tile_telemetry)
                            ActiveScreen.NAVIGATION_OVERLAY -> FocusRequest(R.id.navUrlInput)
                            null -> NO_FOCUS_REQUEST
                        }
                    }
                    ActiveScreen.WEB_RENDER -> FocusRequest(R.id.engineView)
                    ActiveScreen.POCKET -> FocusRequest(R.id.videoFeed)
                    ActiveScreen.SETTINGS -> NO_FOCUS_REQUEST
                    null -> NO_FOCUS_REQUEST
                }
            }
            .filter { it != NO_FOCUS_REQUEST }
            .replay(1)
            .autoConnect(0)

    private val requestAfterFocusLost: Observable<FocusRequest> = focusChanged
            .filter { (_, newFocus) -> invalidIds.contains(newFocus) }
            .flatMap { defaultViewAfterScreenChange.take(1) }

    // TODO ^ SEVERIN ^

    data class State(
            val focusNode: OldFocusNode,
            val defaultFocusMap: HashMap<ScreenControllerStateMachine.ActiveScreen, Int>
    )

    enum class Event {
        ScreenChange,
        RequestFocus // to handle lost focus
    }

    /**
     * OldFocusNode describes quasi-directional focusable paths given viewId
     */
    data class OldFocusNode(
        val viewId: Int,
        val nextFocusUpId: Int? = null,
        val nextFocusDownId: Int? = null,
        val nextFocusLeftId: Int? = null,
        val nextFocusRightId: Int? = null
    ) {
        fun updateViewNodeTree(view: View) {
            assert(view.id == viewId)

            nextFocusUpId?.let { view.nextFocusUpId = it }
            nextFocusDownId?.let { view.nextFocusDownId = it }
            nextFocusLeftId?.let { view.nextFocusLeftId = it }
            nextFocusRightId?.let { view.nextFocusRightId = it }
        }
    }

    // TODO: potential for telemetry?
    private val _state: BehaviorSubject<State> = BehaviorSubject.create()

    private val _events: Subject<Event> = PublishSubject.create()
    val events: Observable<Event> = _events.hide()

    // Keep track of prevScreen to identify screen transitions
    private var prevScreen: ScreenControllerStateMachine.ActiveScreen =
            ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY

    private val _focusUpdate = Observables.combineLatest(
            _state,
            screenController.currentActiveScreen,
            sessionRepo.state,
            pinnedTileRepo.isEmpty,
            pocketRepo.feedState) { state, activeScreen, sessionState, pinnedTilesIsEmpty, pocketState ->
        dispatchFocusUpdates(state.focusNode, activeScreen, sessionState, pinnedTilesIsEmpty, pocketState)
    }

    val focusUpdate: Observable<State> = _focusUpdate.hide()

    init {
        initializeDefaultFocus()
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
//        fun <T> BehaviorSubject<T>.onNextIfNew(value: T) {
//            val currState = this.value as State
//            val newState = value as State
//            if (currState.focusNode.viewId != newState.focusNode.viewId &&
//                    newState.focusNode.viewId != -1)
//                this.onNext(value)
//        }

//            val newState = State(
//                focusNode = OldFocusNode(it.id),
//                defaultFocusMap = _state.value!!.defaultFocusMap)
//
//            _state.onNextIfNew(newState)
        val oldId = oldFocus?.id ?: -1
        val newId = newFocus?.id ?: -1
        focusChanged.onNext(oldId to newId)
    }

    private fun initializeDefaultFocus() {
        val focusMap = HashMap<ScreenControllerStateMachine.ActiveScreen, Int>()
        focusMap[ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY] = R.id.navUrlInput
        focusMap[ScreenControllerStateMachine.ActiveScreen.WEB_RENDER] = R.id.engineView
        focusMap[ScreenControllerStateMachine.ActiveScreen.POCKET] = R.id.videoFeed

        val newState = State(
            focusNode = OldFocusNode(R.id.navUrlInput),
            defaultFocusMap = focusMap)

        _state.onNext(newState)
    }

    @VisibleForTesting
    private fun dispatchFocusUpdates(
            focusNode: OldFocusNode,
            activeScreen: ScreenControllerStateMachine.ActiveScreen,
            sessionState: SessionRepo.State,
            pinnedTilesIsEmpty: Boolean,
            pocketState: PocketVideoRepo.FeedState
    ): State {

        var newState = _state.value!!
        val focusMap = _state.value!!.defaultFocusMap
        when (activeScreen) {
            ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY -> {

                // Check previous screen for defaultFocusMap updates
                when (prevScreen) {
                    ScreenControllerStateMachine.ActiveScreen.WEB_RENDER -> {
                        newState = updateDefaultFocusForOverlayWhenTransitioningFromWebRender(
                                focusMap,
                                sessionState)
                    }
                    ScreenControllerStateMachine.ActiveScreen.POCKET -> {
                        newState = updateDefaultFocusForOverlayWhenTransitioningFromPocket(focusMap)
                    }
                    else -> Unit
                }

                when (focusNode.viewId) {
                    R.id.navUrlInput ->
                        newState = updateNavUrlInputFocusTree(
                                focusNode,
                                sessionState,
                                pinnedTilesIsEmpty,
                                pocketState)
                    R.id.navButtonReload -> {
                        newState = updateReloadButtonFocusTree(focusNode, sessionState)
                    }
                    R.id.navButtonForward -> {
                        newState = updateForwardButtonFocusTree(focusNode, sessionState)
                    }
                    R.id.pocketVideoMegaTileView -> {
                        newState = updatePocketMegaTileFocusTree(focusNode, pinnedTilesIsEmpty)
                    }
                    R.id.megaTileTryAgainButton -> {
                        newState = handleLostFocusInOverlay(
                                focusNode,
                                sessionState,
                                pinnedTilesIsEmpty,
                                pocketState)
                    }
                    R.id.home_tile -> {
                        // If pinnedTiles is empty and current focus is on home_tile, we need to
                        // restore lost focus (this happens when you remove all tiles in the overlay)
                        if (pinnedTilesIsEmpty) {
                            newState = handleLostFocusInOverlay(
                                    focusNode,
                                    sessionState,
                                    pinnedTilesIsEmpty,
                                    pocketState)
                        }
                    }
                    INVALID_VIEW_ID -> {
                        // Focus is lost so default it to navUrlInput and send a [Event.RequestFocus]
                        val newFocusNode = OldFocusNode(R.id.navUrlInput)

                        newState = updateNavUrlInputFocusTree(
                                newFocusNode,
                                sessionState,
                                pinnedTilesIsEmpty,
                                pocketState)
                        _events.onNext(Event.RequestFocus)
                    }
                }
            }
            ScreenControllerStateMachine.ActiveScreen.WEB_RENDER -> {}
            ScreenControllerStateMachine.ActiveScreen.POCKET -> {}
            ScreenControllerStateMachine.ActiveScreen.SETTINGS -> Unit
        }

        if (prevScreen != activeScreen) {
            _events.onNext(Event.ScreenChange)
            prevScreen = activeScreen
        }

        return newState
    }

    private fun updateNavUrlInputFocusTree(
            focusedNavUrlInputNode: OldFocusNode,
            sessionState: SessionRepo.State,
            pinnedTilesIsEmpty: Boolean,
            pocketState: PocketVideoRepo.FeedState
    ): State {

        assert(focusedNavUrlInputNode.viewId == R.id.navUrlInput)

        val nextFocusDownId = when {
            pocketState is PocketVideoRepo.FeedState.FetchFailed -> R.id.megaTileTryAgainButton
            pocketState is PocketVideoRepo.FeedState.Inactive -> {
                if (pinnedTilesIsEmpty) {
                    R.id.navUrlInput
                } else {
                    R.id.tileContainer
                }
            }
            else -> R.id.pocketVideoMegaTileView
        }

        val nextFocusUpId = when {
            sessionState.backEnabled -> R.id.navButtonBack
            sessionState.forwardEnabled -> R.id.navButtonForward
            sessionState.currentUrl != URLs.APP_URL_HOME -> R.id.navButtonReload
            else -> R.id.turboButton
        }

        return State(
            focusNode = OldFocusNode(
                focusedNavUrlInputNode.viewId,
                nextFocusUpId,
                nextFocusDownId),
            defaultFocusMap = _state.value!!.defaultFocusMap)
    }

    private fun updateReloadButtonFocusTree(
            focusedReloadButtonNode: OldFocusNode,
            sessionState: SessionRepo.State
    ): State {

        assert(focusedReloadButtonNode.viewId == R.id.navButtonReload)

        val nextFocusLeftId = when {
            sessionState.forwardEnabled -> R.id.navButtonForward
            sessionState.backEnabled -> R.id.navButtonBack
            else -> R.id.navButtonReload
        }

        return State(
            focusNode = OldFocusNode(
                focusedReloadButtonNode.viewId,
                nextFocusLeftId = nextFocusLeftId),
            defaultFocusMap = _state.value!!.defaultFocusMap)
    }

    private fun updateForwardButtonFocusTree(
            focusedForwardButtonNode: OldFocusNode,
            sessionState: SessionRepo.State
    ): State {

        assert(focusedForwardButtonNode.viewId == R.id.navButtonForward)

        val nextFocusLeftId = when {
            sessionState.backEnabled -> R.id.navButtonBack
            else -> R.id.navButtonForward
        }

        return State(
            focusNode = OldFocusNode(
                focusedForwardButtonNode.viewId,
                nextFocusLeftId = nextFocusLeftId),
            defaultFocusMap = _state.value!!.defaultFocusMap)
    }

    private fun updatePocketMegaTileFocusTree(
            focusedPocketMegatTileNode: OldFocusNode,
            pinnedTilesIsEmpty: Boolean
    ): State {

        assert(focusedPocketMegatTileNode.viewId == R.id.pocketVideoMegaTileView ||
            focusedPocketMegatTileNode.viewId == R.id.megaTileTryAgainButton)

        val nextFocusDownId = when {
            pinnedTilesIsEmpty -> R.id.settingsTileContainer
            else -> R.id.tileContainer
        }

        return State(
            focusNode = OldFocusNode(
                focusedPocketMegatTileNode.viewId,
                nextFocusDownId = nextFocusDownId),
            defaultFocusMap = _state.value!!.defaultFocusMap)
    }

    /**
     * Two possible scenarios for losing focus when in overlay:
     * 1. When all the pinned tiles are removed, tilContainer no longer needs focus
     * 2. When click on [megaTileTryAgainButton]
     */
    private fun handleLostFocusInOverlay(
            lostFocusNode: OldFocusNode,
            sessionState: SessionRepo.State,
            pinnedTilesIsEmpty: Boolean,
            pocketState: PocketVideoRepo.FeedState
    ): State {

        assert(lostFocusNode.viewId == R.id.tileContainer ||
                lostFocusNode.viewId == R.id.megaTileTryAgainButton)

        val viewId = when (pocketState) {
            PocketVideoRepo.FeedState.FetchFailed -> R.id.megaTileTryAgainButton
            PocketVideoRepo.FeedState.Inactive -> R.id.navUrlInput
            else -> R.id.pocketVideoMegaTileView
        }

        val newFocusNode = OldFocusNode(viewId)
        val newState = if (newFocusNode.viewId == R.id.navUrlInput) {
            updateNavUrlInputFocusTree(newFocusNode, sessionState, pinnedTilesIsEmpty, pocketState)
        } else {
            updatePocketMegaTileFocusTree(newFocusNode, pinnedTilesIsEmpty)
        }

        // Request focus on newState
        if (newFocusNode.viewId != lostFocusNode.viewId) {
            _events.onNext(Event.RequestFocus)
        }

        return newState
    }

    private fun updateDefaultFocusForOverlayWhenTransitioningFromWebRender(
        focusMap: HashMap<ScreenControllerStateMachine.ActiveScreen, Int>,
        sessionState: SessionRepo.State
    ): State {

        // It doesn't make sense to be able to transition to WebRender if currUrl == APP_URL_HOME
        assert(sessionState.currentUrl != URLs.APP_URL_HOME)

        focusMap[ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY] = when {
            sessionState.backEnabled -> R.id.navButtonBack
            sessionState.forwardEnabled -> R.id.navButtonForward
            else -> R.id.navButtonReload
        }

        return State(
                focusNode = _state.value!!.focusNode,
                defaultFocusMap = focusMap)
    }

    private fun updateDefaultFocusForOverlayWhenTransitioningFromPocket(
        focusMap: HashMap<ScreenControllerStateMachine.ActiveScreen, Int>
    ): State {
        focusMap[ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY] =
                R.id.pocketVideoMegaTileView

        return State(
            focusNode = _state.value!!.focusNode,
            defaultFocusMap = focusMap)
    }
}
