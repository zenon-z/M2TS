"""Shared helpers for the D1-D6 analysis pipeline (VLDB reviewer response).

Reuses the hand-rolled np.linalg.lstsq OLS/CI pattern already established in
plot_vldb_reviewer.py / scratch_analysis.py rather than adding a statsmodels dependency the
rest of the repo doesn't otherwise use.
"""
from __future__ import annotations

import math

import numpy as np
from scipy import stats

# Every raw-row CSV produced by the E-A..E-E Java experiments shares this exact header
# (RawRunRow.csvHeader() in experiments/support/RawRunRow.java) - keep in sync if that changes.
RAW_ROW_COLUMNS = [
    "config_id", "seed", "f", "mu", "m", "topology", "mpl", "k", "instance_pool_size",
    "properties_per_model", "total_models", "D", "throughput", "completions",
    "measurement_window_s", "frac_blocked", "frac_waiting_room", "wasted_cpu_io_frac",
    "residual_frac", "aborts_per_completion", "aborts_total", "restarts_total",
    "lock_wait_p50", "lock_wait_p95", "lock_wait_p99", "resources_per_logical_txn_mean",
    "terminated_by", "run_wall_s", "k_eff_static", "warmup_discarded_s",
    # Review round 8 (config-integrity follow-up): derived from the run's ACTUAL materialized
    # SimulatorParameters, not the caller's intent - see check_config_hash_consistency()'s
    # docstring and EffectiveConfigHasher.java.
    "config_hash", "processor_count", "closure_size",
]

# Config-level fields: one value per config_id (constant across every seed in that cell).
# config_hash belongs here too (see check_config_hash_consistency()) but is intentionally kept
# out of this list - it's the thing D2 uses to VERIFY the other columns in this list are actually
# constant per config_id, so treating it as pre-trusted here would be circular.
CONFIG_KEY_COLUMNS = [
    "config_id", "f", "mu", "m", "topology", "mpl", "k", "instance_pool_size",
    "properties_per_model", "total_models", "D", "k_eff_static",
]


def fit_weighted_ols(x, y, weights=None):
    """Weighted OLS via the standard sqrt(w)-rescaling trick (no new dependency beyond numpy/
    scipy, matching plot_vldb_reviewer.py's fit_ols()). x is (n, p) WITHOUT an intercept column
    - one is added here. Returns a DICT with keys coeffs, se, cov_matrix, r_squared, adj_r_squared,
    dof, n - or None if underdetermined (n <= p+1). coeffs[0] is the intercept, coeffs[1:] the
    slopes, and se is aligned with coeffs.
    """
    x = np.asarray(x, dtype=float)
    y = np.asarray(y, dtype=float)
    n = len(y)
    if x.ndim == 1:
        x = x.reshape(-1, 1)
    p = x.shape[1] + 1  # +1 for intercept
    if n <= p:
        return None

    design = np.column_stack([np.ones(n), x])
    if weights is not None:
        w = np.asarray(weights, dtype=float)
        w = np.clip(w, 1e-12, None)
        sqrt_w = np.sqrt(w)
        design_w = design * sqrt_w[:, None]
        y_w = y * sqrt_w
    else:
        design_w = design
        y_w = y

    coeffs, ss_res_arr, _, _ = np.linalg.lstsq(design_w, y_w, rcond=None)
    dof = n - p
    if dof <= 0:
        return None

    fitted = design_w @ coeffs
    ss_res = float(np.sum((y_w - fitted) ** 2))
    y_w_mean = np.mean(y_w)
    ss_tot = float(np.sum((y_w - y_w_mean) ** 2))
    r_squared = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    adj_r_squared = 1.0 - (1.0 - r_squared) * (n - 1) / dof if ss_tot > 0 else float("nan")

    sigma_squared = ss_res / dof
    try:
        cov_matrix = sigma_squared * np.linalg.inv(design_w.T @ design_w)
    except np.linalg.LinAlgError:
        return None
    se = np.sqrt(np.diagonal(cov_matrix))

    return {
        "coeffs": coeffs,
        "se": se,
        "cov_matrix": cov_matrix,
        "r_squared": r_squared,
        "adj_r_squared": adj_r_squared,
        "dof": dof,
        "n": n,
    }


def wald_test_equal(fit, index_a, index_b):
    """Test coeffs[index_a] == coeffs[index_b] (e.g. the R=f*mu hypothesis a==b) via a Wald
    t-test using the OLS covariance matrix from fit_weighted_ols(). Returns (t_stat, p_value)."""
    coeffs = fit["coeffs"]
    cov = fit["cov_matrix"]
    diff = coeffs[index_a] - coeffs[index_b]
    var_diff = cov[index_a, index_a] + cov[index_b, index_b] - 2 * cov[index_a, index_b]
    if var_diff <= 0:
        return float("nan"), float("nan")
    se_diff = np.sqrt(var_diff)
    t_stat = diff / se_diff
    p_value = 2 * (1 - stats.t.cdf(abs(t_stat), df=fit["dof"]))
    return t_stat, p_value


def bootstrap_ci(values, statistic_fn, n_boot=500, ci=0.95, random_state=0):
    """Bootstrap-resample `values` (with replacement) n_boot times, apply statistic_fn to each
    resample, and return (point_estimate, ci_lo, ci_hi). Used by D3 for MPL* seed-resampling CIs
    - "bootstrap over seeds (not over cells)" per the reviewer response."""
    values = np.asarray(values)
    if len(values) == 0:
        return float("nan"), float("nan"), float("nan")
    rng = np.random.default_rng(random_state)
    point = statistic_fn(values)
    if len(values) < 2:
        return point, point, point
    boot_stats = np.empty(n_boot)
    for i in range(n_boot):
        resample = rng.choice(values, size=len(values), replace=True)
        boot_stats[i] = statistic_fn(resample)
    alpha = (1.0 - ci) / 2.0
    lo = np.nanquantile(boot_stats, alpha)
    hi = np.nanquantile(boot_stats, 1.0 - alpha)
    return point, lo, hi


def hypergeometric_block_probability(d, k, h):
    """P(blocked | H) = 1 - C(D-H, k) / C(D, k), the M1/A1 anchor - exact, no approximation and no
    fitted term. THE single source of truth for this formula: the P1 parity claim is that two
    independently instrumented systems both land on it, so a divergent second copy in either arm's
    code would be indistinguishable from a real disagreement. Both plot_tgs_validation.py and
    p1_parity_gates.py import this rather than defining their own."""
    if h > d - k:
        return 1.0
    return 1 - math.comb(d - h, k) / math.comb(d, k)


def wilson(successes, n, z=1.96):
    """Wilson score interval on a proportion. Returns (p_hat, lo, hi).

    Preferred over binomial_ci()'s normal approximation wherever a bin can sit near 0 or 1 - which
    is exactly the P1 parity case, where the whole point of the H=0 bin is that the true value is 0
    and the normal approximation degenerates there (zero width at p_hat=0, so it would "contain" 0
    for any sample size and the gate would be vacuous)."""
    if n == 0:
        return 0.0, 0.0, 0.0
    p = successes / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    half = z * np.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return p, max(0.0, center - half), min(1.0, center + half)


def binomial_ci(successes, n, z=1.96):
    """Normal-approximation CI on a proportion (e.g. D3 synthetic-validation coverage) - at
    n=40 replicates the MC standard error on a coverage estimate near 0.95 is
    sqrt(0.95*0.05/40) ~= 0.034 (~3.4 points), wide enough that a single 0.875 reading is not
    automatically a real undershoot - report the CI, not just the point estimate, so that
    distinction is visible instead of asserted. Returns (lo, hi), clipped to [0, 1]."""
    if n <= 0:
        return float("nan"), float("nan")
    p = successes / n
    se = np.sqrt(max(p * (1 - p), 1e-12) / n)
    return max(0.0, p - z * se), min(1.0, p + z * se)


def coefficient_of_variation(values):
    """CV = stddev/mean, the D6 criterion-search comparison statistic. NaN-safe: drops NaNs
    first, returns NaN if fewer than 2 finite values or mean is ~0."""
    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values)]
    if len(values) < 2:
        return float("nan")
    mean = np.mean(values)
    if abs(mean) < 1e-12:
        return float("nan")
    return float(np.std(values, ddof=1) / mean)


def screen_censored(df, terminated_by_col="terminated_by", config_col="config_id"):
    """D2: drop every row belonging to a config_id where ANY seed's terminated_by != TIME_LIMIT
    - a censored seed invalidates the whole config cell for breakpoint fitting, not just that
    seed. Returns (screened_df, dropped_config_ids)."""
    bad_configs = set(
        df.loc[df[terminated_by_col] != "TIME_LIMIT", config_col].unique()
    )
    screened = df[~df[config_col].isin(bad_configs)].copy()
    return screened, bad_configs


def check_residual_frac(df, high_mpl_quantile=0.75, high_residual_threshold=0.3):
    """Review round 3: the sum-to-1 gate can't detect a missing channel, because residual_frac
    is DERIVED as the remainder (1 - blocked - waiting_room - wasted_cpu_io), not independently
    measured - three fractions that individually undercount (exactly what F1 turned out to be,
    for lock_seconds_waiting_room) would inflate residual_frac to compensate and the sum would
    still silently pass. Two independent checks that don't have this blind spot:
      1. residual_frac < 0 is impossible under a correct decomposition (the three measured
         fractions should never individually sum to more than 1) - any negative value here is
         itself evidence of a decomposition bug, regardless of what the total sums to.
      2. A large unexplained residual (>high_residual_threshold) concentrated at high MPL (top
         quartile of the range) is the same signature F1 had: a real mechanism's cost isn't
         being attributed to any of the three tracked channels. Returns
         (negative_residual_rows, large_residual_at_high_mpl_rows).
    """
    negative = df[df["residual_frac"] < 0]

    mpl_cutoff = df["mpl"].quantile(high_mpl_quantile)
    high_mpl = df[df["mpl"] >= mpl_cutoff]
    large_residual_high_mpl = high_mpl[high_mpl["residual_frac"] > high_residual_threshold]

    return negative, large_residual_high_mpl


def assert_decomposition(df, tolerance=0.02):
    """D2's second gate: re-assert frac_blocked + frac_waiting_room + wasted_cpu_io_frac +
    residual_frac == 1 +/- tolerance (already enforced Java-side at write time by
    RawRunRowWriter - this is the standing "second check" the reviewer response asks for).
    Returns the subset of rows that FAIL the check (empty if all pass)."""
    total = (
        df["frac_blocked"] + df["frac_waiting_room"] + df["wasted_cpu_io_frac"] + df["residual_frac"]
    )
    return df[(total - 1.0).abs() > tolerance]


def kaplan_meier(durations, event_observed):
    """Review round 8: Kaplan-Meier survival estimator with right-censoring, hand-rolled to match
    this file's existing no-new-dependency pattern (no lifelines/statsmodels).

    Exists because the naive "mean group lifetime" (SimulationMetrics.getMeanGroupLifetimeMs())
    is severely survivorship-biased: it only sees groups that happen to complete before the run
    ends, so a long-tailed lifetime distribution (the actual phenomenon under study - starving
    lineages from the livelock analysis) is systematically underrepresented. This estimator uses
    every admitted group - completed groups contribute an exact event time, groups still open
    when the run ended contribute a right-censored lower bound (durations/event_observed come
    from AbGroupLifetimeExperiment's per-group CSV export, mirroring SimulationMetrics.
    GroupLifetimeObservation).

    Args:
        durations: array-like of observed times (exact if event_observed=1, a censoring time -
            i.e. a lower bound on the true lifetime - if event_observed=0).
        event_observed: array-like of 0/1, 1 = the event (completion) was observed at that time,
            0 = censored (still open when observation stopped).

    Returns a dict: time (survival curve x-axis, starts at 0.0), survival (S(t) step function),
    se (Greenwood's-formula standard error), ci_lower/ci_upper (95% CI via the log-minus-log
    transform - stays inside [0,1] and behaves better near S=0/1 than a plain Greenwood +-1.96*SE
    band), n_at_risk, n_events (both per step, for reporting/debugging).
    """
    durations = np.asarray(durations, dtype=float)
    event_observed = np.asarray(event_observed, dtype=int)
    if durations.shape != event_observed.shape:
        raise ValueError("durations and event_observed must have the same shape")

    unique_event_times = np.unique(durations[event_observed == 1])

    survival = 1.0
    variance_sum = 0.0  # Greenwood's formula: Var(S(t)) = S(t)^2 * sum(d_i / (n_i*(n_i-d_i)))
    times = [0.0]
    survivals = [1.0]
    se_list = [0.0]
    n_at_risk_list = [len(durations)]
    n_events_list = [0]

    for t in unique_event_times:
        n_at_risk = int(np.sum(durations >= t))
        n_events = int(np.sum((durations == t) & (event_observed == 1)))
        if n_at_risk == 0:
            continue
        survival *= 1.0 - n_events / n_at_risk
        if n_at_risk > n_events:
            variance_sum += n_events / (n_at_risk * (n_at_risk - n_events))
        times.append(float(t))
        survivals.append(survival)
        se_list.append(survival * np.sqrt(variance_sum))
        n_at_risk_list.append(n_at_risk)
        n_events_list.append(n_events)

    times = np.array(times)
    survivals = np.array(survivals)
    se = np.array(se_list)

    with np.errstate(divide="ignore", invalid="ignore"):
        log_neglog_s = np.log(-np.log(survivals))
        se_loglog = se / (survivals * np.abs(np.log(survivals)))
        ci_lower = np.exp(-np.exp(log_neglog_s + 1.96 * se_loglog))
        ci_upper = np.exp(-np.exp(log_neglog_s - 1.96 * se_loglog))
    # log(S)=0 at S=1 (t=0) and S can hit exactly 0 at the last event if nothing survives it -
    # both make the log-minus-log transform undefined; fall back to the point estimate there.
    ci_lower = np.where(np.isfinite(ci_lower), ci_lower, survivals)
    ci_upper = np.where(np.isfinite(ci_upper), ci_upper, survivals)
    ci_lower = np.clip(ci_lower, 0.0, 1.0)
    ci_upper = np.clip(ci_upper, 0.0, 1.0)

    return {
        "time": times,
        "survival": survivals,
        "se": se,
        "ci_lower": ci_lower,
        "ci_upper": ci_upper,
        "n_at_risk": np.array(n_at_risk_list),
        "n_events": np.array(n_events_list),
    }


def km_median_survival_time(km_result):
    """First time the KM survival curve drops to <=0.5 - the median lifetime, robust to
    censoring (unlike a completed-only mean). Returns np.inf if the curve never reaches 0.5
    within the observed data (median lifetime exceeds what this run could measure)."""
    below_half = np.where(km_result["survival"] <= 0.5)[0]
    if len(below_half) == 0:
        return np.inf
    return km_result["time"][below_half[0]]


def check_config_hash_consistency(df, config_col="config_id", hash_col="config_hash"):
    """Review round 8 (config-integrity follow-up): config_hash is written per-row from the
    run's ACTUAL, fully-materialized SimulatorParameters (EffectiveConfigHasher.java), never from
    the caller's intent - see SeedSweepRunnerCopyBuilderRoundTripTest's javadoc for the incident
    this exists to catch mechanically instead of by a human noticing a suspicious coincidence
    (two supposedly-different Config B variants in the VLDB reviewer-response A/B footprint
    investigation silently ran the identical accidental-default config for an entire round of
    analysis before that was caught by hand).

    Two checks, returned as (inconsistent_configs, colliding_hash_groups):
      1. inconsistent_configs: config_ids where not every row shares the same config_hash - i.e.
         seeds within one cell that should be identical (same config, different seed) actually
         ran on different materialized parameters. A DataFrame of (config_id, n_distinct_hashes).
      2. colliding_hash_groups: config_hash values shared by more than one DISTINCT config_id -
         i.e. two cells that were supposed to differ ran the identical materialized parameters
         (exactly the failure mode that motivated this check). A DataFrame of
         (config_hash, config_ids, n_configs).
    """
    per_config_hash_counts = df.groupby(config_col)[hash_col].nunique()
    inconsistent_configs = per_config_hash_counts[per_config_hash_counts > 1].reset_index(
        name="n_distinct_hashes"
    )

    hash_to_configs = df.groupby(hash_col)[config_col].nunique()
    colliding_hashes = hash_to_configs[hash_to_configs > 1].index
    colliding_hash_groups = (
        df[df[hash_col].isin(colliding_hashes)]
        .groupby(hash_col)[config_col]
        .agg(lambda s: sorted(s.unique()))
        .reset_index(name="config_ids")
    )
    colliding_hash_groups["n_configs"] = colliding_hash_groups["config_ids"].map(len)

    return inconsistent_configs, colliding_hash_groups
