/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.nukkit;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.api.event.user.UserDataRecalculateEvent;
import me.lucko.luckperms.common.api.LuckPermsApiProvider;
import me.lucko.luckperms.common.api.implementation.ApiUser;
import me.lucko.luckperms.common.calculator.CalculatorFactory;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.event.AbstractEventBus;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.StandardGroupManager;
import me.lucko.luckperms.common.model.manager.track.StandardTrackManager;
import me.lucko.luckperms.common.model.manager.user.StandardUserManager;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.tasks.CacheHousekeepingTask;
import me.lucko.luckperms.common.tasks.ExpireTemporaryTask;
import me.lucko.luckperms.nukkit.calculator.NukkitCalculatorFactory;
import me.lucko.luckperms.nukkit.context.NukkitContextManager;
import me.lucko.luckperms.nukkit.context.WorldCalculator;
import me.lucko.luckperms.nukkit.inject.PermissionDefault;
import me.lucko.luckperms.nukkit.inject.permissible.LPPermissible;
import me.lucko.luckperms.nukkit.inject.permissible.PermissibleInjector;
import me.lucko.luckperms.nukkit.inject.permissible.PermissibleMonitoringInjector;
import me.lucko.luckperms.nukkit.inject.server.InjectorDefaultsMap;
import me.lucko.luckperms.nukkit.inject.server.InjectorPermissionMap;
import me.lucko.luckperms.nukkit.inject.server.InjectorSubscriptionMap;
import me.lucko.luckperms.nukkit.inject.server.LPDefaultsMap;
import me.lucko.luckperms.nukkit.inject.server.LPPermissionMap;
import me.lucko.luckperms.nukkit.inject.server.LPSubscriptionMap;
import me.lucko.luckperms.nukkit.listeners.NukkitConnectionListener;
import me.lucko.luckperms.nukkit.listeners.NukkitPlatformListener;

import cn.nukkit.Player;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.permission.Permission;
import cn.nukkit.plugin.PluginManager;
import cn.nukkit.plugin.service.ServicePriority;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * LuckPerms implementation for the Nukkit API.
 */
public class LPNukkitPlugin extends AbstractLuckPermsPlugin {
    private final LPNukkitBootstrap bootstrap;

    private NukkitSenderFactory senderFactory;
    private NukkitConnectionListener connectionListener;
    private NukkitCommandExecutor commandManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private NukkitContextManager contextManager;
    private LPSubscriptionMap subscriptionMap;
    private LPPermissionMap permissionMap;
    private LPDefaultsMap defaultPermissionMap;

    public LPNukkitPlugin(LPNukkitBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public LPNukkitBootstrap getBootstrap() {
        return this.bootstrap;
    }

    @Override
    protected void setupSenderFactory() {
        this.senderFactory = new NukkitSenderFactory(this);
    }

    @Override
    protected Set<Dependency> getGlobalDependencies() {
        return EnumSet.of(Dependency.TEXT, Dependency.CAFFEINE, Dependency.OKIO, Dependency.OKHTTP, Dependency.EVENT);
    }

    @Override
    protected ConfigurationAdapter provideConfigurationAdapter() {
        return new NukkitConfigAdapter(this, resolveConfig());
    }

    @Override
    protected void registerPlatformListeners() {
        this.connectionListener = new NukkitConnectionListener(this);
        this.bootstrap.getServer().getPluginManager().registerEvents(this.connectionListener, this.bootstrap);
        this.bootstrap.getServer().getPluginManager().registerEvents(new NukkitPlatformListener(this), this.bootstrap);
    }

    @Override
    protected MessagingFactory<?> provideMessagingFactory() {
        return new MessagingFactory<>(this);
    }

    @Override
    protected void registerCommands() {
        this.commandManager = new NukkitCommandExecutor(this);
        PluginCommand cmd = (PluginCommand) this.bootstrap.getServer().getPluginCommand("luckperms");
        cmd.setExecutor(this.commandManager);
    }

    @Override
    protected void setupManagers() {
        this.userManager = new StandardUserManager(this);
        this.groupManager = new StandardGroupManager(this);
        this.trackManager = new StandardTrackManager(this);
    }

    @Override
    protected CalculatorFactory provideCalculatorFactory() {
        return new NukkitCalculatorFactory(this);
    }

    @Override
    protected void setupContextManager() {
        this.contextManager = new NukkitContextManager(this);
        this.contextManager.registerCalculator(new WorldCalculator(this));
    }

    @Override
    protected void setupPlatformHooks() {
        // inject our own custom permission maps
        Runnable[] injectors = new Runnable[]{
                new InjectorSubscriptionMap(this),
                new InjectorPermissionMap(this),
                new InjectorDefaultsMap(this),
                new PermissibleMonitoringInjector(this, PermissibleMonitoringInjector.Mode.INJECT)
        };

        for (Runnable injector : injectors) {
            injector.run();

            // schedule another injection after all plugins have loaded
            // the entire pluginmanager instance is replaced by some plugins :(
            this.bootstrap.getServer().getScheduler().scheduleDelayedTask(this.bootstrap, injector, 1, true);
        }
    }

    @Override
    protected AbstractEventBus provideEventBus(LuckPermsApiProvider apiProvider) {
        return new NukkitEventBus(this, apiProvider);
    }

    @Override
    protected void registerApiOnPlatform(LuckPermsApi api) {
        this.bootstrap.getServer().getServiceManager().register(LuckPermsApi.class, api, this.bootstrap, ServicePriority.NORMAL);
    }

    @Override
    protected void registerHousekeepingTasks() {
        this.bootstrap.getScheduler().asyncRepeating(new ExpireTemporaryTask(this), 3, TimeUnit.SECONDS);
        this.bootstrap.getScheduler().asyncRepeating(new CacheHousekeepingTask(this), 2, TimeUnit.MINUTES);
    }

    @Override
    protected void performFinalSetup() {
        // register permissions
        try {
            PluginManager pm = this.bootstrap.getServer().getPluginManager();
            PermissionDefault permDefault = getConfiguration().get(ConfigKeys.COMMANDS_ALLOW_OP) ? PermissionDefault.OP : PermissionDefault.FALSE;

            for (CommandPermission p : CommandPermission.values()) {
                pm.addPermission(new Permission(p.getPermission(), null, permDefault.toString()));
            }
        } catch (Exception e) {
            // this throws an exception if the plugin is /reloaded, grr
        }

        // remove all operators on startup if they're disabled
        if (!getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
            Config ops = this.bootstrap.getServer().getOps();
            ops.getKeys(false).forEach(ops::remove);
        }

        // register autoop listener
        if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
            getApiProvider().getEventBus().subscribe(UserDataRecalculateEvent.class, event -> {
                User user = ApiUser.cast(event.getUser());
                Optional<Player> player = getBootstrap().getPlayer(user.getUuid());
                player.ifPresent(this::refreshAutoOp);
            });
        }

        // Load any online users (in the case of a reload)
        for (Player player : this.bootstrap.getServer().getOnlinePlayers().values()) {
            this.bootstrap.getScheduler().executeAsync(() -> {
                try {
                    User user = this.connectionListener.loadUser(player.getUniqueId(), player.getName());
                    if (user != null) {
                        this.bootstrap.getScheduler().executeSync(() -> {
                            try {
                                LPPermissible lpPermissible = new LPPermissible(player, user, this);
                                PermissibleInjector.inject(player, lpPermissible);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Override
    protected void removePlatformHooks() {
        // uninject from players
        for (Player player : this.bootstrap.getServer().getOnlinePlayers().values()) {
            try {
                PermissibleInjector.uninject(player, false);
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
                player.setOp(false);
            }

            final User user = getUserManager().getIfLoaded(player.getUniqueId());
            if (user != null) {
                user.getCachedData().invalidate();
                getUserManager().unload(user);
            }
        }

        // uninject custom maps
        InjectorSubscriptionMap.uninject();
        InjectorPermissionMap.uninject();
        InjectorDefaultsMap.uninject();
        new PermissibleMonitoringInjector(this, PermissibleMonitoringInjector.Mode.UNINJECT).run();
    }

    public void refreshAutoOp(Player player) {
        if (!getConfiguration().get(ConfigKeys.AUTO_OP)) {
            return;
        }

        User user = getUserManager().getIfLoaded(player.getUniqueId());
        boolean value;

        if (user != null) {
            Map<String, Boolean> permData = user.getCachedData().getPermissionData(this.contextManager.getApplicableContexts(player)).getImmutableBacking();
            value = permData.getOrDefault("luckperms.autoop", false);
        } else {
            value = false;
        }

        player.setOp(value);
    }

    private File resolveConfig() {
        File configFile = new File(this.bootstrap.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            this.bootstrap.getDataFolder().mkdirs();
            this.bootstrap.saveResource("config.yml", false);
        }
        return configFile;
    }

    @Override
    public Optional<Contexts> getContextForUser(User user) {
        return this.bootstrap.getPlayer(user.getUuid()).map(player -> this.contextManager.getApplicableContexts(player));
    }

    @Override
    public Stream<Sender> getOnlineSenders() {
        return Stream.concat(
                Stream.of(getConsoleSender()),
                this.bootstrap.getServer().getOnlinePlayers().values().stream().map(p -> getSenderFactory().wrap(p))
        );
    }

    @Override
    public Sender getConsoleSender() {
        return getSenderFactory().wrap(this.bootstrap.getServer().getConsoleSender());
    }

    public NukkitSenderFactory getSenderFactory() {
        return this.senderFactory;
    }

    @Override
    public AbstractConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    @Override
    public NukkitCommandExecutor getCommandManager() {
        return this.commandManager;
    }

    @Override
    public StandardUserManager getUserManager() {
        return this.userManager;
    }

    @Override
    public StandardGroupManager getGroupManager() {
        return this.groupManager;
    }

    @Override
    public StandardTrackManager getTrackManager() {
        return this.trackManager;
    }

    @Override
    public NukkitContextManager getContextManager() {
        return this.contextManager;
    }

    public LPSubscriptionMap getSubscriptionMap() {
        return this.subscriptionMap;
    }

    public void setSubscriptionMap(LPSubscriptionMap subscriptionMap) {
        this.subscriptionMap = subscriptionMap;
    }

    public LPPermissionMap getPermissionMap() {
        return this.permissionMap;
    }

    public void setPermissionMap(LPPermissionMap permissionMap) {
        this.permissionMap = permissionMap;
    }

    public LPDefaultsMap getDefaultPermissionMap() {
        return this.defaultPermissionMap;
    }

    public void setDefaultPermissionMap(LPDefaultsMap defaultPermissionMap) {
        this.defaultPermissionMap = defaultPermissionMap;
    }

}
