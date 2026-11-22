package com.douai.module.feature;

import android.app.Activity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only native quality metadata and the existing player's manual-resolution request. */
final class NativeVideoQuality extends VideoQualityHook.Access {
    final Method factoryMeet, resume, pageSelected, pause, playerGate, mp4Gate;

    private final Class<?> componentType, awemeType, resolutionType, playAbilityType, holderType;
    private final Class<?> wrapperType, sdkPlayerType;
    private final Class<?> sessionWrapperType, sessionType, switchingPlayerType;
    private final Method activity, currentAweme, workTypeAllowed, workId, holderWork;
    private final Method supported, physicalResolution, compare, currentHolder, holderAbility, request;
    private final Method playerProvider, playerWrapper, activePlayer;
    private final Field visible, automatic, undefined, resolutionHeight;
    private final Field videoProvider, sdkPlayer, manualResolver;
    private final Field session, sessionPlayer, primaryPlayer;

    static NativeVideoQuality resolve(ClassLoader loader) throws ReflectiveOperationException {
        if (loader == null) throw new IllegalArgumentException("Native video quality loader is required");
        return new NativeVideoQuality(loader);
    }

    private NativeVideoQuality(ClassLoader loader) throws ReflectiveOperationException {
        componentType = type(loader, "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.resolution.FeedManualResolutionComponent");
        Class<?> base = type(loader, "com.ss.android.ugc.aweme.feed.plato.core.AbsFeedComponent");
        Class<?> factory = type(loader, "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.resolution.FeedManualResolutionComponentFactory");
        Class<?> parameter = type(loader, "com.ss.android.ugc.aweme.feed.param.FeedParam");
        Class<?> lifecycle = type(loader, "com.ss.android.ugc.aweme.feed.plato.core.FeedLifecycleParams");
        Class<?> activityType = type(loader, "androidx.fragment.app.FragmentActivity");
        awemeType = type(loader, "com.ss.android.ugc.aweme.feed.model.Aweme");
        resolutionType = type(loader, "X.0Gbn");
        holderType = type(loader, "com.ss.android.ugc.aweme.feed.adapter.IFeedViewHolder");
        Class<?> ability = type(loader, "com.ss.android.ugc.aweme.o.oplayer.client.player.ability.DIPlayAbility");
        playAbilityType = type(loader, "com.ss.android.ugc.aweme.o.oplayer.client.player.ability.DPlayAbility");
        if (componentType.getSuperclass() != base || !Activity.class.isAssignableFrom(activityType)
                || !holderType.isInterface() || !resolutionType.isEnum()
                || !ability.isInterface() || !ability.isAssignableFrom(playAbilityType))
            throw new NoSuchMethodException("Native video quality hierarchy changed");

        factoryMeet = method(factory, "isMeetCondition", boolean.class, false, parameter);
        resume = method(componentType, "handleResumeP", void.class, false, lifecycle);
        pageSelected = method(componentType, "onPageSelected", void.class, false, int.class);
        pause = method(componentType, "handlePauseP", void.class, false, lifecycle);
        Class<?> player = type(loader, "com.ss.android.ugc.playerkit.exp.PlayerSettingCenter");
        playerGate = method(player, "Ge", boolean.class, true);
        mp4Gate = method(player, "Ne", boolean.class, true);

        visible = field(base, "i", boolean.class, false);
        activity = method(base, "getActivity", activityType, false);
        currentAweme = method(base, "getCurrentAweme", awemeType, false);
        workTypeAllowed = method(type(loader, "X.07yJ"), "LIZIZ", boolean.class, true, awemeType);
        workId = method(awemeType, "getAid", String.class, false);
        supported = method(componentType, "getSupportedResolutionList", List.class, false);
        compare = method(resolutionType, "LIZ", int.class, true, resolutionType, resolutionType);
        automatic = field(resolutionType, "Manual_Auto", resolutionType, true);
        undefined = field(resolutionType, "Manual_Undefine", resolutionType, true);
        // resolutionInt is an obfuscated name, so a rename in a future host release must not take the
        // whole quality feature down with it: bind softly and fall back to the constant name.
        resolutionHeight = optionalIntField(resolutionType, "resolutionInt");

        currentHolder = method(base, "getCurrentViewHolder", holderType, false);
        holderWork = method(holderType, "getAweme", awemeType, false);
        holderAbility = method(type(loader, "X.0H9C"), "LJII", ability, true, holderType);
        request = method(playAbilityType, "LJJIII", void.class, false, resolutionType);

        Class<?> baseAbility = type(loader, "com.ss.android.ugc.aweme.o.oplayer.client.player.ability.DBasePlayAbility");
        Class<?> videoProviderType = type(loader, "com.ss.android.ugc.aweme.o.oplayer.client.player.provider.DVideoInfoProvider");
        Class<?> providerBase = type(loader, "com.ss.android.ugc.aweme.o.oplayer.client.player.provider.DPlayBaseProvider");
        Class<?> playerProviderType = type(loader, "X.0Gsv");
        Class<?> wrapperInterface = type(loader, "X.0Gr5");
        wrapperType = type(loader, "X.0Gi9");
        Class<?> sdkInterface = type(loader, "X.0GmS");
        sdkPlayerType = type(loader, "X.0GaD");
        sessionWrapperType = type(loader, "X.0GmU");
        sessionType = type(loader, "com.ss.android.ugc.aweme.player.sdk.psmv3.session.PlaySessionV3");
        Class<?> sessionBase = type(loader, "X.0GmT");
        switchingPlayerType = type(loader, "X.0GaE");
        Class<?> resolverType = type(loader, "X.0GaG");
        if (!baseAbility.isAssignableFrom(playAbilityType) || !providerBase.isAssignableFrom(videoProviderType)
                || !playerProviderType.isInterface() || !wrapperInterface.isAssignableFrom(wrapperType)
                || !sdkInterface.isAssignableFrom(sdkPlayerType)
                || !sdkInterface.isAssignableFrom(sessionWrapperType)
                || !sdkInterface.isAssignableFrom(switchingPlayerType)
                || !sessionBase.isAssignableFrom(sessionType))
            throw new NoSuchMethodException("Native video quality provider hierarchy changed");
        videoProvider = field(baseAbility, "f", videoProviderType, false);
        playerProvider = method(providerBase, "LLIIIL", playerProviderType, false);
        playerWrapper = method(playerProviderType, "LIZJ", wrapperInterface, false);
        sdkPlayer = field(wrapperType, "LIZ", sdkInterface, false);
        session = field(sessionWrapperType, "b", sessionType, false);
        sessionPlayer = field(sessionBase, "LJIIIIZZ", sdkInterface, false);
        activePlayer = method(switchingPlayerType, "LJIILL", sdkPlayerType, false);
        Class<?> primaryPlayerType = type(loader, "X.0Ggp");
        if (!sdkPlayerType.isAssignableFrom(primaryPlayerType))
            throw new NoSuchMethodException("Native video quality primary player changed");
        primaryPlayer = field(switchingPlayerType, "b", primaryPlayerType, false);
        manualResolver = field(sdkPlayerType, "N", resolverType, false);
        physicalResolution = method(resolverType, "LJFF", resolutionType, false, boolean.class);
    }

    @Override boolean visible(Object component) throws ReflectiveOperationException {
        if (!componentType.isInstance(component) || !visible.getBoolean(component)) return false;
        Object value = activity.invoke(component);
        if (!(value instanceof Activity)) return false;
        Activity host = (Activity) value;
        return !host.isFinishing() && !host.isDestroyed();
    }

    @Override String source(Object component) throws ReflectiveOperationException {
        if (!componentType.isInstance(component)) return null;
        Object aweme = currentAweme.invoke(component);
        if (!awemeType.isInstance(aweme) || !Boolean.TRUE.equals(workTypeAllowed.invoke(null, aweme))) return null;
        Object value = workId.invoke(aweme);
        if (!(value instanceof String)) return null;
        String id = ((String) value).trim();
        if (id.isEmpty()) return null;
        Object holder = currentHolder.invoke(component);
        if (!holderType.isInstance(holder)) return null;
        Object bound = holderWork.invoke(holder);
        if (!awemeType.isInstance(bound)) return null;
        Object boundId = workId.invoke(bound);
        return boundId instanceof String && id.equals(((String) boundId).trim()) ? id : null;
    }

    @Override List<?> supported(Object component) throws ReflectiveOperationException {
        if (!componentType.isInstance(component)) return Collections.emptyList();
        Object value = supported.invoke(component);
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    @Override Object current(Object component) throws ReflectiveOperationException {
        Object ability = ability(component);
        if (ability == null) return null;
        Object player = nativePlayer(ability, false);
        if (player == null) return null;
        Object resolver = manualResolver.get(player);
        if (resolver == null) return null;
        // true reads the physical resolution even while the process manual choice is Auto.
        Object value = physicalResolution.invoke(resolver, true);
        return resolutionType.isInstance(value) ? value : null;
    }

    private Object nativePlayer(Object ability, boolean forRequest) throws ReflectiveOperationException {
        Object provider = videoProvider.get(ability);
        if (provider == null) return null;
        Object nativeProvider = playerProvider.invoke(provider);
        if (nativeProvider == null) return null;
        Object wrapper = playerWrapper.invoke(nativeProvider);
        if (!wrapperType.isInstance(wrapper)) return null;
        return unwrap(sdkPlayer.get(wrapper), forRequest);
    }

    private Object unwrap(Object player, boolean forRequest) throws ReflectiveOperationException {
        for (int depth = 0; player != null && depth < 4; depth++) {
            if (sdkPlayerType.isInstance(player)) return player;
            Object next;
            if (switchingPlayerType.isInstance(player)) {
                // The host chooses b or e from its current h/r state; never assume b is playing.
                next = activePlayer.invoke(player);
                // Its native manual setter still targets b, so do not request changes while e is active.
                if (forRequest && next != primaryPlayer.get(player)) return null;
            } else if (sessionWrapperType.isInstance(player)) {
                Object value = session.get(player);
                if (!sessionType.isInstance(value)) return null;
                next = sessionPlayer.get(value);
            } else return null;
            if (next == player) return null;
            player = next;
        }
        return null;
    }

    /**
     * Ported from douyim's {@code computePinned}: exact height wins, otherwise the tallest level
     * that does not exceed the target, and only when every level is below the target the shortest
     * one. Returns null when the host offers nothing usable, which keeps the native strategy.
     */
    /**
     * Ported from douyim's {@code computePinned}: exact height wins, then the tallest level that
     * does not exceed the target, then the shortest level above it. Returns null when the host
     * offers nothing usable, which keeps the native strategy.
     */
    @Override Object atHeight(List<?> choices, int targetHeight) throws ReflectiveOperationException {
        if (choices == null || choices.isEmpty() || targetHeight <= 0) return null;
        Object auto = automatic.get(null), unknown = undefined.get(null);
        List<Object> levels = new ArrayList<>();
        int[] heights = new int[choices.size()];
        for (Object choice : choices) {
            if (!resolutionType.isInstance(choice) || choice == auto || choice == unknown) continue;
            heights[levels.size()] = heightOf(choice);
            levels.add(choice);
        }
        int index = pickIndex(heights, levels.size(), targetHeight);
        return index < 0 ? null : levels.get(index);
    }

    /**
     * Ported from douyim's {@code computePinned}: exact height wins, then the tallest level that
     * does not exceed the target, then the shortest level above it. {@code -1} means "nothing
     * usable", which keeps the native strategy. Split out so the ordering is testable on the JVM.
     */
    static int pickIndex(int[] heights, int count, int targetHeight) {
        if (heights == null || count <= 0 || targetHeight <= 0) return -1;
        int atMost = -1, above = -1;
        for (int i = 0; i < count && i < heights.length; i++) {
            int height = heights[i];
            if (height <= 0) continue;
            if (height == targetHeight) return i;
            if (height < targetHeight) {
                if (atMost < 0 || height > heights[atMost]) atMost = i;
            } else if (above < 0 || height < heights[above]) {
                above = i;
            }
        }
        return atMost >= 0 ? atMost : above;
    }

    /**
     * Pixel height of a native level. The obfuscated int field is preferred; when a host release
     * renames it the stable {@code Manual_*} constant name still maps to the same height, so a
     * rename degrades to name matching instead of disabling the feature.
     */
    @Override int heightOf(Object level) throws ReflectiveOperationException {
        if (!resolutionType.isInstance(level)) return 0;
        if (resolutionHeight != null) return resolutionHeight.getInt(level);
        return heightByName(level instanceof Enum ? ((Enum<?>) level).name() : String.valueOf(level));
    }

    static int heightByName(String name) {
        if (name == null) return 0;
        if (name.endsWith("_Dolby") || name.startsWith("Manual_Extremly")) return 2160;
        if (name.startsWith("Manual_Extremely_High")) return 2160;
        if (name.startsWith("Manual_Super_High")) return 1440;
        if (name.startsWith("Manual_High")) return 1080;
        if (name.startsWith("Manual_Sub_High")) return 720;
        if (name.startsWith("Manual_Standard")) return 540;
        if (name.startsWith("Manual_SubStand")) return 480;
        if (name.startsWith("Manual_Low")) return 360;
        return 0;
    }

    @Override Object highest(List<?> choices) throws ReflectiveOperationException {
        if (choices == null || choices.isEmpty()) return null;
        Object auto = automatic.get(null), unknown = undefined.get(null), best = null;
        for (Object choice : choices) {
            if (!resolutionType.isInstance(choice) || choice == auto || choice == unknown) continue;
            if (best == null) best = choice;
            else {
                Object order = compare.invoke(null, choice, best);
                if (!(order instanceof Integer)) return null;
                if ((Integer) order > 0) best = choice;
            }
        }
        return best;
    }

    /** True means the native setter was invoked, not that the stream changed. */
    @Override boolean apply(Object component, Object target) throws ReflectiveOperationException {
        if (!resolutionType.isInstance(target)) return false;
        Object ability = ability(component);
        if (ability == null || nativePlayer(ability, true) == null) return false;
        request.invoke(ability, target);
        return true;
    }

    private Object ability(Object component) throws ReflectiveOperationException {
        if (source(component) == null) return null;
        Object holder = currentHolder.invoke(component);
        if (!holderType.isInstance(holder)) return null;
        Object ability = holderAbility.invoke(null, holder);
        return playAbilityType.isInstance(ability) ? ability : null;
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic,
                                 Class<?>... arguments) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic)
            throw new NoSuchMethodException("Native video quality method contract changed");
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name, Class<?> expected, boolean isStatic)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected || Modifier.isStatic(field.getModifiers()) != isStatic)
            throw new NoSuchFieldException("Native video quality field contract changed");
        field.setAccessible(true);
        return field;
    }

    /** Optional binding: a missing or renamed field must not abort the whole hook. */
    private static Field optionalIntField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) return null;
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }
}
