package adubbz.lockdown.gui;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Random;

import com.google.common.collect.Maps;
import cpw.mods.fml.common.*;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.nbt.*;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.storage.ISaveFormat;

import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.WorldInfo;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Level;

import adubbz.lockdown.Lockdown;
import adubbz.lockdown.util.LDLogger;
import adubbz.lockdown.util.LDObfuscationHelper;

public class GuiCreateFixedWorld extends GuiCreateWorld
{
    private boolean createClicked;

    public GuiCreateFixedWorld(GuiScreen guiScreen)
    {
        super(guiScreen);
    }

    @Override
    public void initGui()
    {
        super.initGui();

        if (Lockdown.disableMoreWorldOptions) this.buttonList.remove(3); //More World Options

        if (Lockdown.disableGameMode)
        {
            this.buttonList.remove(2); //Game Mode

            ObfuscationReflectionHelper.setPrivateValue(GuiCreateWorld.class, this, "", LDObfuscationHelper.gameModeDescriptionLine1);
            ObfuscationReflectionHelper.setPrivateValue(GuiCreateWorld.class, this, "", LDObfuscationHelper.gameModeDescriptionLine2);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton)
    {
        if (Lockdown.disableWorldCreation && guiButton.id == 0)
        {
            this.mc.displayGuiScreen((GuiScreen)null);

            if (this.createClicked)
            {
                return;
            }

            this.createClicked = true;

            File mcDataDir = this.mc.mcDataDir;

            String folderName = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, LDObfuscationHelper.folderName);
            String worldName = ((GuiTextField)ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, LDObfuscationHelper.textboxWorldName)).getText().trim();

            String savesDir = mcDataDir.getAbsoluteFile() + File.separator + "saves";
            File fullTemplateDir = new File(mcDataDir.getAbsoluteFile() + File.separator + Lockdown.templateDirectory);

            try
            {
                LDLogger.log(Level.INFO, "Lockdown using template at " + Lockdown.templateDirectory);
                FileUtils.copyDirectory(fullTemplateDir, new File(savesDir + File.separator + folderName));
            }
            catch (IOException e)
            {
                LDLogger.log(Level.ERROR, "The template world does not exist at " + Lockdown.templateDirectory, e);
                return;
            }

            ISaveFormat isaveformat = this.mc.getSaveLoader();
            isaveformat.renameWorld(folderName, worldName);

            WorldSettings worldsettings = null;     // Default will just use the template's world settings.

            if(Lockdown.enableOverridingTerrainGen)
            {
                LDLogger.log(Level.INFO, "Lockdown will override the template's terrain generation settings");

                // This mostly follows what a Vanilla instance would already do, excepting that we have to rip out all
                // the private fields. We are going to populate worldsettings ourselves. One exception is we go out of
                // our way to check that commands need to be enabled.
                GuiTextField seedTextField = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, "field_146335_h");
                String gameModeName = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, "field_146342_r");
                boolean generateStructures = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, "field_146341_s");
                boolean bonusChest = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, "field_146337_w");
                boolean commandsAllowed = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, "field_146344_y");
                int terrainType = ObfuscationReflectionHelper.getPrivateValue(GuiCreateWorld.class, this, "field_146331_K");

                long defaultSeed = (new Random()).nextLong();
                String seedText = seedTextField.getText();

                if (!MathHelper.stringNullOrLengthZero(seedText))
                {
                    try
                    {
                        long j = Long.parseLong(seedText);

                        if (j != 0L)
                        {
                            defaultSeed = j;
                        }
                    }
                    catch (NumberFormatException numberformatexception)
                    {
                        defaultSeed = (long)seedText.hashCode();
                    }
                }

                File savedLevelDatPath = new File(savesDir + File.separator + folderName + File.separator + "level.dat");
                NBTTagCompound levelDat = null;
                try {
                    levelDat = CompressedStreamTools.readCompressed(new FileInputStream(savedLevelDatPath));
                }
                catch(IOException e) {
                    LDLogger.log(Level.ERROR, "Could not load level.dat to patch with old FML info!");
                    return;
                }

                LDLogger.log(Level.INFO, "Modifying copied template level.dat with user-specified world settings.");

                NBTTagCompound dataSection = levelDat.getCompoundTag("Data");
                dataSection.setTag("allowCommands", new NBTTagByte((byte) (commandsAllowed ? 1 : 0)));
                dataSection.setTag("generatorName", new NBTTagString(WorldType.worldTypes[terrainType].getWorldTypeName()));
                //dataSection.setTag("generatorOptions", new NBTTagString(TODO: World generator options));
                dataSection.setTag("RandomSeed", new NBTTagLong(defaultSeed));
                dataSection.setTag("generateStructures", new NBTTagByte((byte) (generateStructures ? 1 : 0)));

                WorldSettings.GameType gametype = WorldSettings.GameType.getByName(gameModeName);
                dataSection.setTag("gameType", new NBTTagInt(gametype.getID()));
                levelDat.setTag("Data", dataSection);

                try {
                    LDLogger.log(Level.INFO, "Saved adjusted level.dat");
                    CompressedStreamTools.writeCompressed(levelDat, new FileOutputStream(savedLevelDatPath));
                }
                catch(FileNotFoundException e) {
                    LDLogger.log(Level.ERROR, "Could not find level.dat to save final copy. This is especially strange since we just opened it before!");
                    return;
                }
                catch(IOException e) {
                    LDLogger.log(Level.ERROR, "Could not manipulate level.dat to save final copy.");
                    return;
                }


                // Previously, we would try to use the built-in functions to properly initialize the world, but the
                // result had inconsistent problems. For example, it would screw up block IDs, and if anything drifted,
                // you'd find your walls replaced with molten bronze, or whatever happened with other mods. So we're
                // going to just hit the level.dat with a heavy hand and override the terrain fields.
                //
                // Note: I still have no clue how to enable the bonus chest. One thing at a time.


/*
                WorldSettings.GameType gametype = WorldSettings.GameType.getByName(gameModeName);
                worldsettings = new WorldSettings(defaultSeed, gametype, generateStructures, bonusChest, WorldType.worldTypes[terrainType]);
                worldsettings.func_82750_a(this.field_146334_a);

                if(commandsAllowed)
                {
                    worldsettings.enableCommands();
                }

                // This normally happens once in the instance is started, but this code is skipped if a save already
                // exists. We're going to flatten the copied save settings with the ones the user just defined.
                WorldInfo worldInfo = new WorldInfo(worldsettings, folderName);
                ISaveFormat saveLoader = new AnvilSaveConverter(new File(this.mc.mcDataDir, "saves"));
                ISaveHandler isavehandler = saveLoader.getSaveLoader(folderName, false);

                // Let's preserve the FML section because it has block ID maps
                NBTTagCompound originalLevelDat = null;
                try {
                    originalLevelDat = CompressedStreamTools.readCompressed(new FileInputStream(new File(fullTemplateDir + File.separator + "level.dat")));
                }
                catch(IOException e) {
                    LDLogger.log(Level.ERROR, "Could not load level.dat to patch with old FML info!");
                    return;
                }

                isavehandler.saveWorldInfo(worldInfo);

                // Now let's load the new level.dat and append all the sections that are not "Data" from the templated
                // level.dat into it. What could possibly go wrong???
                LDLogger.log(Level.ERROR, "Making adjustments to virgin level.dat using template level.dat");
                File outLevelData = new File(savesDir + File.separator + folderName + File.separator + "level.dat");
                NBTTagCompound newLevelDat = null;
                try {
                    newLevelDat = CompressedStreamTools.readCompressed(new FileInputStream(outLevelData));
                }
                catch(IOException e) {
                    LDLogger.log(Level.ERROR, "Could not load new level.dat to patch with old FML info! This is especially strange since we just opened it before!");
                    return;
                }

                // TODO: Dynamically search for tags that are not Data and save them all. That would be more robust.
                newLevelDat.removeTag("FML");
                newLevelDat.removeTag("Forge");
                newLevelDat.setTag("FML", originalLevelDat.getCompoundTag("FML"));
                newLevelDat.setTag("Forge", originalLevelDat.getCompoundTag("Forge"));
                newLevelDat.setTag("DidLockdownPatch", new NBTTagInt(1));

                // Ahh, but we are not done! We have to assert the player's coordinates from the data section!
                // These are getting lost from the original save.
                NBTTagCompound oldDataSection = originalLevelDat.getCompoundTag("Data");
                NBTTagCompound oldPlayerSection = oldDataSection.getCompoundTag("Player");

                NBTTagCompound newDataSection = newLevelDat.getCompoundTag("Data");
                newDataSection.setTag("Player", oldPlayerSection);
                newLevelDat.setTag("Data", newDataSection);

                // One more save to disk.
                try {
                    LDLogger.log(Level.ERROR, "Saved adjusted level.dat");
                    CompressedStreamTools.writeCompressed(newLevelDat, new FileOutputStream(outLevelData));
                }
                catch(FileNotFoundException e) {
                    LDLogger.log(Level.ERROR, "Could not find level.dat to save final copy. This is especially strange since we just opened it before!");
                    return;
                }
                catch(IOException e) {
                    LDLogger.log(Level.ERROR, "Could not manipulate level.dat to save final copy.");
                    return;
                }

                // Okay, clever guy. Try to load this world info in order to trigger the FML container to jiggle
                // block and item IDs if they are different in the world than what the instance loaded at startup.
                // This should prevent problems with the template world turning inside-out from block ID mismatches.
                //
                // Shamelessly stolen from FMLCommonHandler. We just run the code it would have run if this were the server!
                //
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
                Map<String,NBTBase> additionalProperties = Maps.newHashMap();
                worldInfo.setAdditionalProperties(additionalProperties);

                SaveHandler saveHandler = (SaveHandler) isavehandler;

                LDLogger.log(Level.INFO, "Jockeying block and item IDs from loaded instance with template so we start using the templated IDs instead.");
                for (ModContainer mc : Loader.instance().getModList())
                {
                    if (mc instanceof InjectedModContainer)
                    {
                        WorldAccessContainer wac = ((InjectedModContainer)mc).getWrappedWorldAccessContainer();
                        if (wac != null)
                        {
                            wac.readData(saveHandler, worldInfo, additionalProperties, newLevelDat.getCompoundTag(mc.getModId()));
                        }
                    }
                }
                ////////////////////////////////////////////////////////////////////////////////////////////////////////
*/


            }

            if (this.mc.getSaveLoader().canLoadWorld(folderName))
            {
                // Note: worldsettings now should just be null
                // TODO: just set worldsettings param to null
                this.mc.launchIntegratedServer(folderName, worldName, worldsettings);
            }
        }
        else
        {
            super.actionPerformed(guiButton);
        }
    }
}
