package com.yoda.codingagent.core.api;

/**
 * Command execution authority captured when a turn starts.
 */
public enum CommandAccessMode {
    /** Only commands explicitly allowed by the local policy may run. */
    RESTRICTED,
    /** Policy-approved commands run; uncertain commands require a user decision. */
    ASK,
    /** Command policy checks are bypassed for this turn. */
    FULL_ACCESS
}
