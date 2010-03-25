package org.glassfish.tests.ejb.timertest;

import javax.ejb.*;
import javax.naming.InitialContext;

/**
 * @author Marina Vatkina
 */
@Stateless
public class SimpleEjb {

    private static volatile boolean timeoutWasCalled = false;
    private static volatile boolean autotimeoutWasCalled = false;

    public void createTimer() throws Exception {
        System.err.println("In SimpleEjb:createTimer()");
        TimerService timerSvc = (TimerService) new InitialContext().lookup("java:comp/TimerService");
        Timer t = timerSvc.createTimer(2, "timer01");
    }

    public boolean verifyTimer() {
        return timeoutWasCalled && autotimeoutWasCalled;
    }

    @Timeout
    private void timeout(Timer t) {

        System.err.println("in SimpleEjb: timeout "  + t.getInfo());
        timeoutWasCalled = true;
    }

    @Schedule(second="*", minute="*", hour="*")
    public void autotest() {
        System.err.println("IN AUTO-TIMEOUT!!!");
        autotimeoutWasCalled = true;
    }
}
