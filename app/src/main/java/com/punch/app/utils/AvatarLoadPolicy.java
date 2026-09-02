package com.punch.app.utils;

final class AvatarLoadPolicy {
    private AvatarLoadPolicy() {
    }

    enum RequestManagerChoice {
        VIEW,
        APPLICATION_CONTEXT,
        SKIP
    }

    static RequestManagerChoice chooseRequestManager(boolean clearing, boolean ownerDestroyed) {
        if (!ownerDestroyed) {
            return RequestManagerChoice.VIEW;
        }
        return clearing ? RequestManagerChoice.APPLICATION_CONTEXT : RequestManagerChoice.SKIP;
    }
}
