package de.tum.tssimulator.locking.core;

public class GlobalLockBudget {
    private final int maxTokens;
    private final int totalResources;
    private int usedTokens = 0;

    public GlobalLockBudget(int maxTokens, int totalResources) {
        this.maxTokens = maxTokens;
        this.totalResources = totalResources;
    }

    public boolean tryAcquire(int tokensNeeded) {
        if (usedTokens + tokensNeeded > maxTokens) return false;
        usedTokens += tokensNeeded;
        return true;
    }

    public void release(int tokens) {
        usedTokens -= tokens;
        if (usedTokens < 0) usedTokens = 0;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getTotalResources() {
        return totalResources;
    }

    public int getAvailableTokens() {
        return maxTokens - usedTokens;
    }
}


