package org.qainsights.jmeter.ai.fix;

import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Session-memory confirmation gate for real HTTP requests initiated by Make it pass. */
public final class HostConfirmation {
    private static final Set<Set<String>> CONFIRMED = new java.util.HashSet<>();

    private HostConfirmation() {
    }

    public static boolean confirm(Component parent, Set<String> hosts) {
        Set<String> hostSet = Collections.unmodifiableSet(new TreeSet<>(hosts == null ? Set.of() : hosts));
        synchronized (CONFIRMED) {
            if (CONFIRMED.contains(hostSet)) {
                return true;
            }
        }
        AtomicBoolean accepted = new AtomicBoolean();
        Runnable show = () -> {
            String list = hostSet.isEmpty() ? "no HTTP host could be determined" : String.join(", ", hostSet);
            int choice = JOptionPane.showConfirmDialog(parent,
                    "Make it pass will send real requests from 1 user to: " + list,
                    "Make it pass", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            accepted.set(choice == JOptionPane.OK_OPTION);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(show);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException e) {
                return false;
            }
        }
        if (accepted.get()) {
            synchronized (CONFIRMED) {
                CONFIRMED.add(hostSet);
            }
        }
        return accepted.get();
    }
}
