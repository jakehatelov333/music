package cn.edu.seig.vibemusic.security;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs external side effects only after the surrounding database transaction commits.
 *
 * <p>Without an active transaction the callback runs immediately. This keeps direct
 * service calls (and focused unit tests) working while production writes cannot revoke
 * a session or consume a code before their database changes are durable.
 */
public final class TransactionCallbacks {

    private TransactionCallbacks() {
    }

    public static void afterCommit(Runnable action) {
        afterCommit(List.of(action), List.of());
    }

    public static void afterCommit(Runnable commitAction, Runnable rollbackAction) {
        afterCommit(List.of(commitAction), List.of(rollbackAction));
    }

    public static void afterCommit(List<Runnable> commitActions, List<Runnable> rollbackActions) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runAll(commitActions);
            return;
        }
        AtomicBoolean handled = new AtomicBoolean();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (handled.compareAndSet(false, true)) {
                    runAll(commitActions);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED && handled.compareAndSet(false, true)) {
                    runAll(rollbackActions);
                }
            }
        });
    }

    private static void runAll(List<Runnable> actions) {
        List<RuntimeException> failures = new ArrayList<>();
        for (Runnable action : actions) {
            if (action == null) {
                continue;
            }
            try {
                action.run();
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }
}
