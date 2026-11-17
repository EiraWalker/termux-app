package com.termux.app.privateadb;

import org.junit.Test;
import static org.junit.Assert.*;

public class NsdRecoveryPolicyTest {
    @Test public void staleFailureMustLeaveWindowForNewPort() throws Exception {
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        p.offer(39323);
        assertEquals(Integer.valueOf(39323), p.next(1500));
        assertFalse(p.finished(1));
        p.offer(36719);
        assertEquals(Integer.valueOf(36719), p.next(3000));
        assertTrue(p.finished(0));
    }
    @Test public void duplicateCandidateNeverBusyRetries() {
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        for (int i = 0; i < 1000; i++) p.offer(39323);
        assertEquals(Integer.valueOf(39323), p.next(1500));
        assertFalse(p.finished(1));
        for (int i = 0; i < 1000; i++) p.offer(39323);
        assertNull(p.next(3000));
    }
    @Test public void candidatesAreBounded() {
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        for (int i = 1; i < 200; i++) p.offer(i);
        int count = 0; while (p.next(100) != null) count++;
        assertEquals(32, count);
    }
    @Test public void codeTwoPausesBeforeAnyOtherCandidate() {
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        p.offer(39323); p.offer(36719);
        assertNotNull(p.next(2000));
        assertTrue(p.finished(2));
        assertNull(p.next(2001));
    }
    @Test public void deadlineNeverExtendsAfterFailedCandidate() {
        assertEquals(45000, AutostartPolicy.WINDOW_MS);
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        p.offer(39323); assertNotNull(p.next(1500));
        assertFalse(p.finished(17)); p.offer(36719);
        assertNull(p.next(45000)); assertNull(p.next(50000));
        assertEquals(45000, p.deadline);
    }
    @Test public void stopRejectsLateCandidate() {
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        p.offer(39323); p.close(); p.offer(36719); assertNull(p.next(3000));
    }
    @Test public void concurrentDiscoveriesSurviveOutstandingResolve() throws Exception {
        NsdRecoveryPolicy.Services<Integer> services = new NsdRecoveryPolicy.Services<>();
        services.put("first", 1); assertEquals(Integer.valueOf(1), services.next());
        Thread a = new Thread(() -> services.put("second", 2));
        Thread b = new Thread(() -> services.put("third", 3));
        a.start(); b.start(); a.join(); b.join();
        java.util.Set<Integer> resolved = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) resolved.add(services.next());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(1,2,3)), resolved);
    }
    @Test public void serviceUpdatesAndLossKeepRoundRobinBounded() {
        NsdRecoveryPolicy.Services<Integer> services = new NsdRecoveryPolicy.Services<>();
        services.put("a", 39323); services.put("b", 36719); services.put("a", 46649);
        assertEquals(Integer.valueOf(46649), services.next());
        services.remove("b"); assertEquals(Integer.valueOf(46649), services.next());
        services.clear(); assertNull(services.next());
        for (int i = 0; i < 32; i++) assertTrue(services.put("s" + i, i));
        assertFalse(services.put("overflow", 100));
    }
    @Test public void remoteHostIsNotAnObservedLocalCandidate() throws Exception {
        java.net.InetAddress own = java.net.InetAddress.getByAddress(new byte[]{10,0,0,2});
        java.net.InetAddress remote = java.net.InetAddress.getByAddress(new byte[]{10,0,0,3});
        NsdRecoveryPolicy p = new NsdRecoveryPolicy(45000);
        if (AutostartPolicy.localEndpoint(remote, 39323, java.util.Collections.singletonList(own))) p.offer(39323);
        assertNull(p.next(2000));
        p.offer(0); p.offer(65536); assertNull(p.next(2001));
    }
}
