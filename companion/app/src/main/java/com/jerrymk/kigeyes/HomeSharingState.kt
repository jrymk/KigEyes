package com.jerrymk.kigeyes

enum class HomeShareAction { SETUP, ALLOW, PAUSE }

/** Main-screen wording describes actual sharing separately from permission and installation. */
data class HomeSharingState(val title: String,val detail: String,val button: String,
                            val action: HomeShareAction,val enabled: Boolean=true) {
    companion object {
        fun from(worker: MirrorWorkerState,busy: Boolean=false): HomeSharingState {
            val state=when {
                worker.checking->HomeSharingState("Checking screen sharing…","Looking for the phone worker.","Checking…",HomeShareAction.SETUP,false)
                worker.stopping || !worker.available->HomeSharingState("Set up screen sharing","Use USB debugging once to install the worker. Sharing stays off during setup.","Set up screen sharing",HomeShareAction.SETUP)
                !worker.allowed->HomeSharingState("Sharing paused","Your connection stays ready. Resume whenever you need it.","Resume sharing",HomeShareAction.ALLOW)
                worker.streaming->HomeSharingState("Sharing your screen",if(worker.networkRoute=="companion_wifi") "Live over KigEyes Wi-Fi. Use any app on your phone." else "Live in your glasses. Use any app on your phone.","Pause sharing",HomeShareAction.PAUSE)
                worker.connected->HomeSharingState("Ready to share","Enable Phone screen in the glasses to view this phone.","Pause sharing",HomeShareAction.PAUSE)
                else->HomeSharingState("Waiting for KigEyes","Connect below. Screen sharing will resume when KigEyes is ready.","Pause sharing",HomeShareAction.PAUSE)
            }
            return if(busy)state.copy(button="Updating…",enabled=false) else state
        }
    }
}
