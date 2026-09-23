package cc.nitea.neoforge;

import cc.nitea.NiteaConsent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Nitea as a mod of its own. Every mod using Nitea on NeoForge bundles this jar with Jar-in-Jar; NeoForge loads a
 * single copy (the newest), so the player sees one Nitea, whatever the number of mods using it. It owns the
 * player's consent: the question is asked on behalf of Nitea, not of any single mod.
 */
@Mod(NiteaMod.MOD_ID)
public final class NiteaMod {
    public static final String MOD_ID = "nitea";

    public NiteaMod() {
        NiteaConsent.useGameDir(FMLPaths.GAMEDIR.get());
    }
}
