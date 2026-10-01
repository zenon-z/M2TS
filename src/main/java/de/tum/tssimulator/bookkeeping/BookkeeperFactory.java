package de.tum.tssimulator.bookkeeping;

import java.util.List;

import de.tum.tssimulator.core.Transaction;

public interface BookkeeperFactory {
    List<BookkeeperTransaction> createFrom(Transaction parent);
}
