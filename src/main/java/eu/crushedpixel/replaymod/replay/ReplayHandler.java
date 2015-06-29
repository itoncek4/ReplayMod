package eu.crushedpixel.replaymod.replay;

import com.mojang.authlib.GameProfile;
import eu.crushedpixel.replaymod.ReplayMod;
import eu.crushedpixel.replaymod.entities.CameraEntity;
import eu.crushedpixel.replaymod.events.KeyframesModifyEvent;
import eu.crushedpixel.replaymod.holders.*;
import eu.crushedpixel.replaymod.registry.PlayerHandler;
import eu.crushedpixel.replaymod.settings.RenderOptions;
import eu.crushedpixel.replaymod.utils.ReplayFile;
import eu.crushedpixel.replaymod.utils.ReplayFileIO;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.util.ReportedException;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ReplayHandler {

    public static long lastExit = 0;
    private static NetworkManager networkManager;
    private static Minecraft mc = Minecraft.getMinecraft();
    private static OpenEmbeddedChannel channel;
    private static int realTimelinePosition = 0;
    private static Keyframe selectedKeyframe;
    private static boolean inPath = false;
    private static CameraEntity cameraEntity;
    private static List<Keyframe> keyframes = new ArrayList<Keyframe>();
    private static boolean inReplay = false;
    private static Entity currentEntity = null;
    private static Position lastPosition = null;

    private static float cameraTilt = 0;

    private static KeyframeSet[] keyframeRepository = new KeyframeSet[]{};

    /**
     * The file currently being played.
     */
    private static ReplayFile currentReplayFile;

    public static KeyframeSet[] getKeyframeRepository() {
        return keyframeRepository;
    }

    public static void setKeyframeRepository(KeyframeSet[] repo, boolean write) {
        keyframeRepository = repo;
        if(write) {
            try {
                File tempFile = File.createTempFile(ReplayFile.ENTRY_PATHS, "json");

                ReplayFileIO.write(repo, tempFile);

                ReplayMod.replayFileAppender.registerModifiedFile(tempFile, ReplayFile.ENTRY_PATHS, getReplayFile());
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static MarkerKeyframe[] getMarkers() {
        List<MarkerKeyframe> markers = new ArrayList<MarkerKeyframe>();
        for(Keyframe kf : keyframes) {
            if(kf instanceof MarkerKeyframe) markers.add((MarkerKeyframe)kf);
        }
        return markers.toArray(new MarkerKeyframe[markers.size()]);
    }

    public static void setMarkers(MarkerKeyframe[] m, boolean write) {
        for(Keyframe kf : new ArrayList<Keyframe>(keyframes)) {
            if(kf instanceof MarkerKeyframe) keyframes.remove(kf);
        }
        Collections.addAll(keyframes, m);

        if(write) {
            try {
                File tempFile = File.createTempFile(ReplayFile.ENTRY_MARKERS, "json");

                ReplayFileIO.write(m, tempFile);

                ReplayMod.replayFileAppender.registerModifiedFile(tempFile, ReplayFile.ENTRY_MARKERS, getReplayFile());
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        FMLCommonHandler.instance().bus().post(new KeyframesModifyEvent(keyframes));
    }

    public static void useKeyframePresetFromRepository(int index) {
        useKeyframePreset(keyframeRepository[index].getKeyframes());
    }

    public static void useKeyframePreset(Keyframe[] kfs) {
        MarkerKeyframe[] markers = getMarkers();

        keyframes = new ArrayList<Keyframe>(Arrays.asList(kfs));

        Collections.addAll(keyframes, markers);

        if(!(selectedKeyframe instanceof MarkerKeyframe)) selectedKeyframe = null;

        FMLCommonHandler.instance().bus().post(new KeyframesModifyEvent(keyframes));
    }

    public static void spectateEntity(Entity e) {
        if(e == null) {
            spectateCamera();
        }
        else {
            currentEntity = e;
            if (mc.getRenderViewEntity() != currentEntity) {
                mc.setRenderViewEntity(currentEntity);
            }
        }
    }

    public static void spectateCamera() {
        if(currentEntity != null) {
            Position prev = new Position(currentEntity);
            cameraEntity.movePath(prev);
        }
        currentEntity = cameraEntity;
        mc.setRenderViewEntity(cameraEntity);
    }

    public static boolean isCamera() {
        return currentEntity == cameraEntity;
    }

    public static Entity getCurrentEntity() {
        return currentEntity;
    }

    public static void startPath(RenderOptions renderOptions) {
        if(!ReplayHandler.isInPath()) {
            try {
                ReplayProcess.startReplayProcess(renderOptions);
            } catch (ReportedException e) {
                // We have to manually unwrap OOM errors as Minecraft doesn't handle them when they're wrapped
                Throwable cause = e;
                while (cause != null) {
                    if (cause instanceof OutOfMemoryError) {
                        // Nevertheless save the crash report in case we actually need it
                        Minecraft minecraft = Minecraft.getMinecraft();
                        CrashReport crashReport = e.getCrashReport();
                        minecraft.addGraphicsAndWorldToCrashReport(crashReport);
                        Bootstrap.printToSYSOUT(crashReport.getCompleteReport());
                        File folder = new File(minecraft.mcDataDir, "crash-reports");
                        File file = new File(folder, "crash-" + (new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss")).format(new Date()) + "-client.txt");
                        crashReport.saveToFile(file);
                        throw (OutOfMemoryError) cause;
                    }
                    cause = e.getCause();
                }
                throw e;
            }
        }
    }

    public static void interruptReplay() {
        ReplayProcess.stopReplayProcess(false);
    }

    public static boolean isInPath() {
        return inPath;
    }

    public static void setInPath(boolean replaying) {
        inPath = replaying;
    }

    public static CameraEntity getCameraEntity() {
        return cameraEntity;
    }

    public static void setCameraEntity(CameraEntity entity) {
        if(entity == null) return;
        if (cameraEntity != null) {
            FMLCommonHandler.instance().bus().unregister(cameraEntity);
        }
        cameraEntity = entity;
        FMLCommonHandler.instance().bus().register(cameraEntity);
        spectateCamera();
    }

    public static float getCameraTilt() {
        return cameraTilt;
    }

    public static void setCameraTilt(float tilt) {
        cameraTilt = tilt;
    }

    public static void addCameraTilt(float tilt) {
        cameraTilt += tilt;
    }

    public static void sortKeyframes() {
        Collections.sort(keyframes, new KeyframeComparator());
    }

    public static void toggleMarker() {
        if(selectedKeyframe instanceof MarkerKeyframe) removeKeyframe(selectedKeyframe);
        else {
            Position pos = new Position(mc.getRenderViewEntity());
            int timestamp = ReplayMod.replaySender.currentTimeStamp();
            addKeyframe(new MarkerKeyframe(pos, timestamp, null));
        }
    }

    public static void addKeyframe(Keyframe keyframe) {
        keyframes.add(keyframe);
        selectKeyframe(keyframe);

        if(keyframe instanceof PositionKeyframe) {
            Float a = null;
            Float b;

            for(Keyframe kf : keyframes) {
                if(!(kf instanceof PositionKeyframe)) continue;
                PositionKeyframe pkf = (PositionKeyframe)kf;
                Position pos = pkf.getPosition();
                b = pos.getYaw() % 360;
                if(a != null) {
                    float diff = b-a;
                    if(Math.abs(diff) > 180) {
                        b = a - (360 - diff) % 360;
                        pos.setYaw(b);
                        pkf.setPosition(pos);
                    }
                }
                a = b;
            }
        }

        FMLCommonHandler.instance().bus().post(new KeyframesModifyEvent(keyframes));
    }

    public static void removeKeyframe(Keyframe keyframe) {
        keyframes.remove(keyframe);
        if(keyframe == selectedKeyframe) {
            selectKeyframe(null);
        } else {
            sortKeyframes();
        }

        FMLCommonHandler.instance().bus().post(new KeyframesModifyEvent(keyframes));
    }

    public static int getKeyframeIndex(TimeKeyframe timeKeyframe) {
        int index = 0;
        for(Keyframe kf : keyframes) {
            if(kf == timeKeyframe) return index;
            else if(kf instanceof TimeKeyframe) index++;
        }
        return -1;
    }

    public static int getKeyframeIndex(PositionKeyframe posKeyframe) {
        int index = 0;
        for(Keyframe kf : keyframes) {
            if(kf == posKeyframe) return index;
            else if(kf instanceof PositionKeyframe) index++;
        }
        return -1;
    }

    public static int getPosKeyframeCount() {
        int size = 0;
        for(Keyframe kf : keyframes) {
            if(kf instanceof PositionKeyframe) size++;
        }
        return size;
    }

    public static int getTimeKeyframeCount() {
        int size = 0;
        for(Keyframe kf : keyframes) {
            if(kf instanceof TimeKeyframe) size++;
        }
        return size;
    }

    public static TimeKeyframe getClosestTimeKeyframeForRealTime(int realTime, int tolerance) {
        List<TimeKeyframe> found = new ArrayList<TimeKeyframe>();
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof TimeKeyframe)) continue;
            if(Math.abs(kf.getRealTimestamp() - realTime) <= tolerance) {
                found.add((TimeKeyframe) kf);
            }
        }

        TimeKeyframe closest = null;

        for(TimeKeyframe kf : found) {
            if(closest == null || Math.abs(closest.getTimestamp() - realTime) > Math.abs(kf.getRealTimestamp() - realTime)) {
                closest = kf;
            }
        }
        return closest;
    }

    public static PositionKeyframe getClosestPlaceKeyframeForRealTime(int realTime, int tolerance) {
        List<PositionKeyframe> found = new ArrayList<PositionKeyframe>();
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof PositionKeyframe)) continue;
            if(Math.abs(kf.getRealTimestamp() - realTime) <= tolerance) {
                found.add((PositionKeyframe) kf);
            }
        }

        PositionKeyframe closest = null;

        for(PositionKeyframe kf : found) {
            if(closest == null || Math.abs(closest.getRealTimestamp() - realTime) > Math.abs(kf.getRealTimestamp() - realTime)) {
                closest = kf;
            }
        }
        return closest;
    }

    public static MarkerKeyframe getClosestMarkerForRealTime(int realTime, int tolerance) {
        List<MarkerKeyframe> found = new ArrayList<MarkerKeyframe>();
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof MarkerKeyframe)) continue;
            if(Math.abs(kf.getRealTimestamp() - realTime) <= tolerance) {
                found.add((MarkerKeyframe) kf);
            }
        }

        MarkerKeyframe closest = null;

        for(MarkerKeyframe kf : found) {
            if(closest == null || Math.abs(closest.getRealTimestamp() - realTime) > Math.abs(kf.getRealTimestamp() - realTime)) {
                closest = kf;
            }
        }
        return closest;
    }

    public static PositionKeyframe getPreviousPositionKeyframe(int realTime) {
        if(keyframes.isEmpty()) return null;
        PositionKeyframe backup = null;
        List<PositionKeyframe> found = new ArrayList<PositionKeyframe>();
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof PositionKeyframe)) continue;
            if(kf.getRealTimestamp() < realTime) {
                found.add((PositionKeyframe)kf);
            } else if(kf.getRealTimestamp() == realTime) {
                backup = (PositionKeyframe)kf;
            }
        }

        if(found.size() > 0)
            return found.get(found.size() - 1); //last element is nearest
        else return backup;
    }

    public static PositionKeyframe getNextPositionKeyframe(int realTime) {
        if(keyframes.isEmpty()) return null;
        PositionKeyframe backup = null;
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof PositionKeyframe)) continue;
            if(kf.getRealTimestamp() > realTime) {
                return (PositionKeyframe)kf; //first found element is next
            } else if(kf.getRealTimestamp() == realTime) {
                backup = (PositionKeyframe)kf;
            }
        }
        return backup;
    }

    public static TimeKeyframe getPreviousTimeKeyframe(int realTime) {
        if(keyframes.isEmpty()) return null;
        TimeKeyframe backup = null;
        List<TimeKeyframe> found = new ArrayList<TimeKeyframe>();
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof TimeKeyframe)) continue;
            if(kf.getRealTimestamp() < realTime) {
                found.add((TimeKeyframe)kf);
            } else if(kf.getRealTimestamp() == realTime) {
                backup = (TimeKeyframe)kf;
            }
        }

        if(found.size() > 0)
            return found.get(found.size() - 1); //last element is nearest
        else return backup;
    }

    public static TimeKeyframe getNextTimeKeyframe(int realTime) {
        if(keyframes.isEmpty()) return null;
        TimeKeyframe backup = null;
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof TimeKeyframe)) continue;
            if(kf.getRealTimestamp() > realTime) {
                return (TimeKeyframe) kf; //first found element is next
            } else if(kf.getRealTimestamp() == realTime) {
                backup = (TimeKeyframe)kf;
            }
        }
        return backup;
    }

    public static MarkerKeyframe getPreviousMarkerKeyframe(int realTime) {
        if(keyframes.isEmpty()) return null;
        MarkerKeyframe backup = null;
        List<MarkerKeyframe> found = new ArrayList<MarkerKeyframe>();
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof MarkerKeyframe)) continue;
            if(kf.getRealTimestamp() < realTime) {
                found.add((MarkerKeyframe)kf);
            } else if(kf.getRealTimestamp() == realTime) {
                backup = (MarkerKeyframe)kf;
            }
        }

        if(found.size() > 0)
            return found.get(found.size() - 1); //last element is nearest
        else return backup;
    }

    public static MarkerKeyframe getNextMarkerKeyframe(int realTime) {
        if(keyframes.isEmpty()) return null;
        MarkerKeyframe backup = null;
        for(Keyframe kf : keyframes) {
            if(!(kf instanceof MarkerKeyframe)) continue;
            if(kf.getRealTimestamp() > realTime) {
                return (MarkerKeyframe) kf; //first found element is next
            } else if(kf.getRealTimestamp() == realTime) {
                backup = (MarkerKeyframe)kf;
            }
        }
        return backup;
    }

    public static List<Keyframe> getKeyframes() {
        return new ArrayList<Keyframe>(keyframes);
    }

    public static void resetKeyframes(boolean resetMarkers) {
        MarkerKeyframe[] markers = getMarkers();
        keyframes = new ArrayList<Keyframe>();

        if(!resetMarkers) {
            Collections.addAll(keyframes, markers);

            if(!(selectedKeyframe instanceof MarkerKeyframe))
                selectKeyframe(null);
        } else {
            selectKeyframe(null);
        }

        FMLCommonHandler.instance().bus().post(new KeyframesModifyEvent(keyframes));
    }

    public static boolean isSelected(Keyframe kf) {
        return kf == selectedKeyframe;
    }

    public static void selectKeyframe(Keyframe kf) {
        selectedKeyframe = kf;
        sortKeyframes();
    }

    public static boolean isInReplay() {
        return inReplay;
    }

    public static void startReplay(File file) {
        startReplay(file, true);
    }

    public static void startReplay(File file, boolean asyncMode) {

        ReplayMod.chatMessageHandler.initialize();
        mc.ingameGUI.getChatGUI().clearChatMessages();
        resetKeyframes(true);

        if(ReplayMod.replaySender != null) {
            ReplayMod.replaySender.terminateReplay();
        }

        if(channel != null) {
            channel.close();
        }

        setCameraTilt(0);

        networkManager = new NetworkManager(EnumPacketDirection.CLIENTBOUND) {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                t.printStackTrace();
            }
        };
        INetHandlerPlayClient pc = new NetHandlerPlayClient(mc, null, networkManager, new GameProfile(UUID.randomUUID(), "Player"));
        networkManager.setNetHandler(pc);

        channel = new OpenEmbeddedChannel(networkManager);
        channel.attr(NetworkDispatcher.FML_DISPATCHER).set(new NetworkDispatcher(networkManager));

        // Open replay
        try {
            currentReplayFile = new ReplayFile(file);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }

        KeyframeSet[] paths = currentReplayFile.paths().get();
        ReplayHandler.setKeyframeRepository(paths == null ? new KeyframeSet[0] : paths, false);

        MarkerKeyframe[] markers = currentReplayFile.markers().get();
        ReplayHandler.setMarkers(markers == null ? new MarkerKeyframe[0] : markers, false);

        PlayerVisibility visibility = currentReplayFile.visibility().get();
        PlayerHandler.loadPlayerVisibilityConfiguration(visibility);

        ReplayMod.replaySender = new ReplaySender(currentReplayFile, asyncMode);
        channel.pipeline().addFirst(ReplayMod.replaySender);
        channel.pipeline().fireChannelActive();

        try {
            ReplayMod.overlay.resetUI(true);
        } catch(Exception e) {
            // TODO: Fix exceptionsudo
        }

        //Load lighting and trigger update
        ReplayMod.replaySettings.setLightingEnabled(ReplayMod.replaySettings.isLightingEnabled());

        inReplay = true;
    }

    public static void restartReplay() {
        mc.ingameGUI.getChatGUI().clearChatMessages();

        if(channel != null) {
            channel.close();
        }

        networkManager = new NetworkManager(EnumPacketDirection.CLIENTBOUND) {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                t.printStackTrace();
            }
        };
        INetHandlerPlayClient pc = new NetHandlerPlayClient(mc, null, networkManager, new GameProfile(UUID.randomUUID(), "Player"));
        networkManager.setNetHandler(pc);

        channel = new OpenEmbeddedChannel(networkManager);
        channel.attr(NetworkDispatcher.FML_DISPATCHER).set(new NetworkDispatcher(networkManager));

        channel.pipeline().addFirst(ReplayMod.replaySender);
        channel.pipeline().fireChannelActive();

        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    ReplayMod.overlay.resetUI(false);
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        });

        inReplay = true;
    }

    public static void endReplay() {
        if(ReplayMod.replaySender != null) {
            ReplayMod.replaySender.terminateReplay();
        }

        if (currentReplayFile != null) {
            try {
                currentReplayFile.close();

                File markerFile = File.createTempFile(ReplayFile.ENTRY_MARKERS, "json");
                ReplayFileIO.write(getMarkers(), markerFile);
                ReplayMod.replayFileAppender.registerModifiedFile(markerFile, ReplayFile.ENTRY_MARKERS, ReplayHandler.getReplayFile());
            } catch (IOException e) {
                e.printStackTrace();
            }
            currentReplayFile = null;
        }

        resetKeyframes(true);

        inReplay = false;
    }

    public static void setInReplay(boolean inReplay1) {
        inReplay = inReplay1;
    }

    public static Keyframe getSelectedKeyframe() {
        return selectedKeyframe;
    }

    public static int getRealTimelineCursor() {
        return realTimelinePosition;
    }

    public static void setRealTimelineCursor(int pos) {
        realTimelinePosition = pos;
    }

    public static Position getLastPosition() {
        return lastPosition;
    }

    public static void setLastPosition(Position position) {
        lastPosition = position;
    }

    public static File getReplayFile() {
        return currentReplayFile == null ? null : currentReplayFile.getFile();
    }

    public static TimeKeyframe getFirstTimeKeyframe() {
        Keyframe sel = getSelectedKeyframe();
        sortKeyframes();
        for(Keyframe k : getKeyframes()) {
            if(k instanceof TimeKeyframe) {
                selectKeyframe(sel);
                return (TimeKeyframe)k;
            }
        }
        selectKeyframe(sel);
        return null;
    }

    public static PositionKeyframe getFirstPositionKeyframe() {
        Keyframe sel = getSelectedKeyframe();
        sortKeyframes();
        for(Keyframe k : getKeyframes()) {
            if(k instanceof PositionKeyframe) {
                selectKeyframe(sel);
                return (PositionKeyframe)k;
            }
        }
        selectKeyframe(sel);
        return null;
    }

    public static TimeKeyframe getLastTimeKeyframe() {
        ArrayList<Keyframe> rev = new ArrayList<Keyframe>(getKeyframes());
        Collections.reverse(rev);

        for(Keyframe k : rev) {
            if(k instanceof TimeKeyframe) {
                return (TimeKeyframe)k;
            }
        }
        return null;
    }

    public static void syncTimeCursor(boolean shiftMode) {
        selectKeyframe(null);

        int curTime = ReplayMod.replaySender.currentTimeStamp();

        int prevTime, prevRealTime;

        TimeKeyframe keyframe;

        //if shift is down, it will refer to the previous Time Keyframe instead of the last one
        if(shiftMode) {
            int realTime = getRealTimelineCursor();
            keyframe = getPreviousTimeKeyframe(realTime);
        } else {
            keyframe = getLastTimeKeyframe();
        }

        if(keyframe == null) {
            prevTime = 0;
            prevRealTime = 0;
        } else {
            prevTime = keyframe.getTimestamp();
            prevRealTime = keyframe.getRealTimestamp();
        }

        int newCursorPos = prevRealTime+(curTime-prevTime);

        setRealTimelineCursor(newCursorPos);
    }
}
