package com.termux.app.privateadb;

import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceLeaseTest {
    static final String BOOT = "01234567-89ab-cdef-0123-456789abcdef";
    static final String HASH = new String(new char[64]).replace('\0', 'a');
    MaintenanceLease.Request request(long deadline) {
        return new MaintenanceLease.Request(HASH, BOOT, deadline, HASH, HASH, 100, 101);
    }
    @Test public void stopSafetyExpiryBootAndVersionAlwaysPreemptAck() {
        assertGate("revoked", BOOT, 100, 100, true, false);
        assertGate("revoked", BOOT, 100, 100, false, true);
        assertGate("expired", BOOT, 500, 100, false, false);
        assertGate("expired", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 100, 100, false, false);
        assertGate("version_mismatch", BOOT, 100, 102, false, false);
        MaintenanceLease l = new MaintenanceLease(request(500), 123, "pending");
        assertEquals("active", l.ack(123, BOOT, 100, 101, false, false, true, false));
        assertEquals("not_owner", l.release(124, HASH));
        assertEquals("active", l.phase);
        assertEquals("not_owner", l.release(123, new String(new char[64]).replace('\0', 'b')));
        assertEquals("released", l.release(123, HASH));
        assertEquals("released", l.ack(123, BOOT, 100, 101, false, false, true, false));
    }
    private void assertGate(String expected, String boot, long now, long installed, boolean stop, boolean safety) {
        MaintenanceLease l = new MaintenanceLease(request(500), 123, "pending");
        assertEquals(expected, l.ack(123, boot, now, installed, stop, safety, true, false));
        assertEquals(expected, l.phase);
    }
    @Test public void acquisitionIsPendingUntilWorkerHasDrainedAndClosed() {
        MaintenanceLease lease = new MaintenanceLease(request(500), 123, "pending");
        assertEquals("pending", lease.phase);
        assertEquals("draining", lease.ack(123, BOOT, 100, 100, false, false, false, false));
        assertEquals("pending", lease.phase);
        assertEquals("cleanup_pending", lease.ack(123, BOOT, 100, 100, false, false, true, true));
        assertEquals("pending", lease.phase);
        assertEquals("active", lease.ack(123, BOOT, 100, 100, false, false, true, false));
        assertEquals("active", lease.phase);
    }
}
