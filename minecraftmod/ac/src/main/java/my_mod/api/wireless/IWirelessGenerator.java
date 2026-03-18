package my_mod.api.wireless;

/**
 * Platform-neutral interface for wireless generator blocks.
 */
public interface IWirelessGenerator {
    /**
     * Get how much energy this generator can provide.
     * @param req how much energy is required
     * @return provided energy (0 &lt;= ret &lt;= req)
     */
    double getProvidedEnergy(double req);

    /** Maximum energy transmitted each tick. */
    double getGeneratorBandwidth();
}
