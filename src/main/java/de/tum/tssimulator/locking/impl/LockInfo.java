package de.tum.tssimulator.locking.impl;

import java.util.LinkedHashSet;
import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockType;

class LockInfo {
    // LinkedHashSet, not HashSet: Transaction declares no hashCode(), so a HashSet here iterates in
    // identity-hash order, which differs on every JVM. Behaviour-neutral TODAY only because this set
    // holds at most one transaction (EXCLUSIVE-only locking) - so nothing can observe the order.
    // Per-operation S/X modes make co-holders routine, at which point every iteration of this set
    // becomes order-dependent in fact rather than in principle. Changed while the semantics are
    // still simple and the change is provably inert, so that a later divergence cannot be
    // ambiguous between "the compatibility rewrite changed behaviour" and "iteration order moved".
    //
    // Insertion order is the right order here, unlike CprSchema.computeAllClosures() which needed an
    // explicit sort: there, insertion order was itself a HashSet traversal order - deterministic but
    // arbitrary. Here it is the order transactions actually acquired the lock, which is meaningful
    // and already deterministic.
    final Set<Transaction> holders = new LinkedHashSet<>();
    LockType type;

    LockInfo(LockType type, Transaction t) {
        this.type = type;
        this.holders.add(t);
    }
}
