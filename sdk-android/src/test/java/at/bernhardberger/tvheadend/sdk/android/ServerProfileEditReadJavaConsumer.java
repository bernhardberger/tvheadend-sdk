package at.bernhardberger.tvheadend.sdk.android;

final class ServerProfileEditReadJavaConsumer {
    private ServerProfileEditReadJavaConsumer() {}

    static int inspect(ServerProfileEditReadResult result) {
        if (result == ServerProfileEditReadResult.Missing.INSTANCE) {
            return 0;
        }
        if (result == ServerProfileEditReadResult.Unavailable.INSTANCE) {
            return 1;
        }
        if (result instanceof ServerProfileEditReadResult.Anonymous anonymous) {
            if (!"edit.invalid".equals(anonymous.getHost()) || anonymous.getPort() != 4_242) {
                throw new AssertionError("Unexpected anonymous edit fields");
            }
            return 2;
        }
        if (result instanceof ServerProfileEditReadResult.Password password) {
            if (!"edit.invalid".equals(password.getHost()) || password.getPort() != 4_242) {
                throw new AssertionError("Unexpected password endpoint fields");
            }
            if (!"edit-user".equals(password.getUsername()) ||
                    !" exact password ".equals(password.getPassword())) {
                throw new AssertionError("Unexpected password edit fields");
            }
            return 3;
        }
        throw new AssertionError("Unknown profile edit result");
    }
}
