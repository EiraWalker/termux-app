package com.termux.app.privateadb;

/** Android-free lease decision. Only the service's serial worker may acknowledge. */
final class MaintenanceLease {
    static final long MAX_MS = 600000;
    static final class Request {
        final String token, boot, profile, release;
        final long deadline, baseline, target;
        Request(String token, String boot, long deadline, String profile, String release,
                long baseline, long target) {
            this.token = token; this.boot = boot; this.deadline = deadline;
            this.profile = profile; this.release = release; this.baseline = baseline; this.target = target;
        }
        boolean same(Request r) {
            return r != null && token.equals(r.token) && boot.equals(r.boot) && deadline == r.deadline &&
                profile.equals(r.profile) && release.equals(r.release) && baseline == r.baseline && target == r.target;
        }
    }
    final Request request;
    final int owner;
    String phase;
    MaintenanceLease(Request request, int owner, String phase) {
        this.request = request; this.owner = owner; this.phase = phase;
    }
    boolean live() { return "pending".equals(phase) || "active".equals(phase); }
    String validate(int uid, String boot, long now, long installed, boolean stop, boolean safety) {
        if (uid != owner) return "not_owner";
        if (!live()) return phase;
        if (stop || safety) phase = "revoked";
        else if (!request.boot.equals(boot) || now < 0 || now >= request.deadline) phase = "expired";
        else if (installed != request.baseline && installed != request.target) phase = "version_mismatch";
        return phase;
    }
    String release(int uid, String token) {
        if (uid != owner || !request.token.equals(token)) return "not_owner";
        if (live()) phase = "released";
        return phase;
    }
    String ack(int uid, String boot, long now, long installed, boolean stop, boolean safety,
               boolean drained, boolean windowOwned) {
        String valid = validate(uid, boot, now, installed, stop, safety);
        if (!live() || "not_owner".equals(valid)) return valid;
        if (!drained) return "draining";
        if (windowOwned) return "cleanup_pending";
        phase = "active";
        return phase;
    }
}
