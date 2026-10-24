package app.trouvetout.core.platform

/** Static facts about the device, provided by the host app (used for the model variant choice, ADR-003). */
public interface DeviceInfo {
    /** Total physical memory in bytes as reported by the OS, or 0 when unknown. */
    public val totalRamBytes: Long
    public val osName: String
    public val osVersion: String
}
