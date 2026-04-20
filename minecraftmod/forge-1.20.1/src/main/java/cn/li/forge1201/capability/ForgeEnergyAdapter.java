package cn.li.forge1201.capability;

import cn.li.mcmod.energy.IEnergyCapable;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Adapter that wraps AcademyCraft's IEnergyCapable and exposes it as Forge's IEnergyStorage.
 * This allows external mods using Forge Energy to interact with AC energy converters.
 *
 * Conversion: IF (Imaginary Energy) ↔ FE (Forge Energy)
 * Conversion rate is configurable (default: 1 IF = 4 FE)
 */
public class ForgeEnergyAdapter implements IEnergyStorage {
    private final IEnergyCapable acEnergy;
    private final double conversionRate;

    /**
     * Create a Forge Energy adapter for an AC energy capable block.
     *
     * @param acEnergy The AC energy capable implementation
     * @param conversionRate Conversion rate (1 IF = X FE)
     */
    public ForgeEnergyAdapter(IEnergyCapable acEnergy, double conversionRate) {
        this.acEnergy = acEnergy;
        this.conversionRate = conversionRate;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (!canReceive()) {
            return 0;
        }

        // Convert FE → IF
        double ifAmount = maxReceive / conversionRate;
        int ifReceived = acEnergy.receiveEnergy((int) ifAmount, simulate);

        // Convert back to FE for return value
        return (int) (ifReceived * conversionRate);
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (!canExtract()) {
            return 0;
        }

        // Convert FE → IF
        double ifAmount = maxExtract / conversionRate;
        int ifExtracted = acEnergy.extractEnergy((int) ifAmount, simulate);

        // Convert back to FE for return value
        return (int) (ifExtracted * conversionRate);
    }

    @Override
    public int getEnergyStored() {
        // Convert IF → FE
        int ifStored = acEnergy.getEnergyStored();
        return (int) (ifStored * conversionRate);
    }

    @Override
    public int getMaxEnergyStored() {
        // Convert IF → FE
        int ifMax = acEnergy.getMaxEnergyStored();
        return (int) (ifMax * conversionRate);
    }

    @Override
    public boolean canExtract() {
        return acEnergy.canExtract();
    }

    @Override
    public boolean canReceive() {
        return acEnergy.canReceive();
    }
}
