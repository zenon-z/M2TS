package de.tum.tssimulator.lifecycle;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;

public interface BlockedTransactionRetrier {
    void retrySpecificTransactions(Set<Transaction> txnsToRetry);
}
