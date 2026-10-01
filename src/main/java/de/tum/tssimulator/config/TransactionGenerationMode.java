package de.tum.tssimulator.config;

/**
 * Controls how transactions are generated.
 * <p>
 * When this is {@code TRANSACTION_PROGRAMS}, {@code transactionSizeProfile},
 * {@code maxTransactionResources}, and {@code useConstantTransactionSize} are ignored:
 * transaction size is determined by the chosen program's number of resource variables.
 */
public enum TransactionGenerationMode {
    RANDOM_OPERATIONS,
    TRANSACTION_PROGRAMS
}
