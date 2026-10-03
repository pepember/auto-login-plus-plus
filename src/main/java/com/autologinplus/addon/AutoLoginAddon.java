package com.autologinplus.addon;

import com.autologinplus.addon.modules.AutoLoginPlusPlus;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AutoLoginAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Auto Login++ Addon");

        Modules.get().add(new AutoLoginPlusPlus());
    }

    @Override
    public void onRegisterCategories() {
    }

    @Override
    public String getPackage() {
        return "com.autologinplus.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("pepember", "auto-login-plus-plus");
    }
}
