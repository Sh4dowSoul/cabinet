package com.afollestad.cabinet.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InflateException;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.cabinet.BuildConfig;
import com.afollestad.cabinet.R;
import com.afollestad.cabinet.cab.PickerCab;
import com.afollestad.cabinet.cab.base.BaseCab;
import com.afollestad.cabinet.cab.base.BaseFileCab;
import com.afollestad.cabinet.file.LocalFile;
import com.afollestad.cabinet.file.base.File;
import com.afollestad.cabinet.fragments.DirectoryFragment;
import com.afollestad.cabinet.fragments.NavigationDrawerFragment;
import com.afollestad.cabinet.fragments.WelcomeFragment;
import com.afollestad.cabinet.ui.base.NetworkedActivity;
import com.afollestad.cabinet.utils.APKIconDownloader;
import com.afollestad.cabinet.utils.Pins;
import com.afollestad.cabinet.utils.ThemeUtils;
import com.afollestad.cabinet.utils.Utils;
import com.afollestad.materialdialogs.MaterialDialog;
import com.anjlab.android.iab.v3.BillingProcessor;
import com.anjlab.android.iab.v3.TransactionDetails;
import com.crashlytics.android.Crashlytics;
import com.getbase.floatingactionbutton.FloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;

import io.fabric.sdk.android.Fabric;

public class MainActivity extends NetworkedActivity implements BillingProcessor.IBillingHandler {

    public interface FabListener {
        public abstract void onFabPressed(int action);
    }

    private BillingProcessor mBP; // used for donations
    private BaseCab mCab; // the current contextual action bar, saves state throughout fragments

    public FloatingActionsMenu mFab; // the floating blue add/paste button
    private FabListener mFabListener; // a callback used to notify DirectoryFragment of fab press
    public BaseFileCab.PasteMode fabPasteMode = BaseFileCab.PasteMode.DISABLED;
    private boolean fabDisabled; // flag indicating whether fab should stay hidden while scrolling
    public boolean shouldAttachFab; // used during config change, tells fragment to reattach to cab
    public boolean pickMode; // flag indicating whether user is picking a file for another app
    private DrawerLayout mDrawerLayout;
    public Toolbar mToolbar;

    public BaseCab getCab() {
        return mCab;
    }

    public void setCab(BaseCab cab) {
        mCab = cab;
    }

    public void toggleFab(boolean hide) {
        if (fabDisabled) getFab().hide(false);
        else if (hide) getFab().hide(true);
        else getFab().show(true);
    }

    public void waitForFab() {
        while (true) {
            if (isFinishing() || getFab() != null) break;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (isDestroyed()) break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void disableFab(boolean disable, boolean force) {
        if (getFab() == null) return;
        if (!disable) {
            if (getFab().getVisibility() == View.GONE)
                getFab().setVisibility(View.VISIBLE);
            getFab().show(true);
        } else {
            getFab().hide(true);
            if (force) {
                getFab().setVisibility(View.GONE);
            } else {
                getFab().hide(true);
            }
        }
        fabDisabled = disable;
    }

    public void setFabListener(FabListener mFabListener) {
        this.mFabListener = mFabListener;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openOptionsMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (mCab != null && mCab.isActive())
            outState.putSerializable("cab", mCab);
        outState.putSerializable("fab_pastemode", fabPasteMode);
        outState.putBoolean("fab_disabled", fabDisabled);
        outState.putBoolean("fab_frame_visible", findViewById(R.id.outerFrame).getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else if (getFab().isExpanded()) {
            getFab().collapse();
        } else if (getFragmentManager().getBackStackEntryCount() == 0) {
            super.onBackPressed();
        } else getFragmentManager().popBackStack();
    }

    @Override
    protected boolean hasNavDrawer() {
        return true;
    }

    public void invalidateToolbarMenu(boolean cabShown) {
        for (int i = 0; i < mToolbar.getMenu().size(); i++) {
            mToolbar.getMenu().getItem(i).setVisible(!cabShown);
        }
    }

    public DrawerLayout getDrawerLayout() {
        if (mDrawerLayout == null)
            mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        return mDrawerLayout;
    }

    public FloatingActionsMenu getFab() {
        if (mFab == null)
            mFab = (FloatingActionsMenu) findViewById(R.id.fab_actions);
        return mFab;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) {
            Fabric.with(this, new Crashlytics());
        }
        setContentView(R.layout.activity_main);

        mToolbar = (Toolbar) findViewById(R.id.toolbar_actionbar);
        mToolbar.setPopupTheme(getThemeUtils().getPopupTheme());
        setSupportActionBar(mToolbar);

        LinearLayout toolbarDirectory = (LinearLayout) findViewById(R.id.toolbar_directory);
        toolbarDirectory.setBackgroundColor(getThemeUtils().primaryColor());

        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey("cab")) {
                mCab = (BaseCab) savedInstanceState.getSerializable("cab");
                if (mCab instanceof BaseFileCab) {
                    shouldAttachFab = true;
                } else {
                    if (mCab instanceof PickerCab) pickMode = true;
                    mCab.setContext(this).start();
                }
            }
            fabPasteMode = (BaseFileCab.PasteMode) savedInstanceState.getSerializable("fab_pastemode");
            fabDisabled = savedInstanceState.getBoolean("fab_disabled");
        }

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("shown_welcome", false)) {
            finishDrawerSetup();
        } else {
            mToolbar.setNavigationIcon(R.drawable.ic_intro_menu);
        }


        FrameLayout navDrawerFrame = (FrameLayout) findViewById(R.id.nav_drawer_frame);
        int navDrawerMargin = getResources().getDimensionPixelSize(R.dimen.nav_drawer_margin);
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int navDrawerWidthLimit = getResources().getDimensionPixelSize(R.dimen.nav_drawer_width_limit);
        int navDrawerWidth = displayMetrics.widthPixels - navDrawerMargin;
        if (navDrawerWidth > navDrawerWidthLimit) {
            navDrawerWidth = navDrawerWidthLimit;
        }
        navDrawerFrame.setLayoutParams(new DrawerLayout.LayoutParams(navDrawerWidth, DrawerLayout.LayoutParams.MATCH_PARENT, Gravity.START));
        navDrawerFrame.setBackgroundColor(getThemeUtils().primaryColorDark());

        mDrawerLayout.setStatusBarBackgroundColor(getThemeUtils().primaryColorDark());

        setupFab(findViewById(R.id.fab_actions), -1);
        setupFab(findViewById(R.id.actionNewFile), 0);
        setupFab(findViewById(R.id.actionNewFolder), 1);
        setupFab(findViewById(R.id.actionNewConnection), 2);

        if (savedInstanceState != null && savedInstanceState.getBoolean("fab_frame_visible")) {
            showOuterFrame();
        }

        mBP = new BillingProcessor(this, "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlPBB2hP/R0PrXtK8NPeDX7QV1fvk1hDxPVbIwRZLIgO5l/ZnAOAf8y9Bq57+eO5CD+ZVTgWcAVrS/QsiqDI/MwbfXcDydSkZLJoFofOFXRuSL7mX/jNwZBNtH0UrmcyFx1RqaHIe9KZFONBWLeLBmr47Hvs7dKshAto2Iy0v18kN48NqKxlWtj/PHwk8uIQ4YQeLYiXDCGhfBXYS861guEr3FFUnSLYtIpQ8CiGjwfU60+kjRMmXEGnmhle5lqzj6QeL6m2PNrkbJ0T9w2HM+bR7buHcD8e6tHl2Be6s/j7zn1Ypco/NCbqhtPgCnmLpeYm8EwwTnH4Yei7ACR7mXQIDAQAB", this);

        final Drawable fallback = getResources().getDrawable(R.drawable.ic_file_image);
        fallback.setColorFilter(getThemeUtils().primaryColor(), PorterDuff.Mode.SRC_ATOP);
        final DisplayImageOptions options = new DisplayImageOptions.Builder()
                .resetViewBeforeLoading(true)
                .showImageOnLoading(fallback)
                .showImageForEmptyUri(fallback)
                .showImageOnFail(fallback)
                .cacheInMemory(true)
                .cacheOnDisk(false)
                .build();
        final ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(getApplicationContext())
                .defaultDisplayImageOptions(options)
                .imageDownloader(new APKIconDownloader(this))
                .build();
        ImageLoader.getInstance().init(config);
    }

    public void finishDrawerSetup() {
        NavigationDrawerFragment mNavigationDrawerFragment = (NavigationDrawerFragment) getFragmentManager().findFragmentById(R.id.navigation_drawer);
        mNavigationDrawerFragment.setUp(mDrawerLayout, mToolbar);
    }

    private void setupFab(View view, int action) {
        final ThemeUtils theme = getThemeUtils();
        if (view instanceof FloatingActionButton) {
            FloatingActionButton btn = (FloatingActionButton) view;
            btn.setColorNormal(theme.accentColor());
            btn.setColorPressed(theme.accentColorDark());
            btn.setTag(action);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mFabListener != null)
                        mFabListener.onFabPressed((Integer) v.getTag());
                    ((FloatingActionsMenu) v.getParent()).toggle();
                }
            });
        } else {
            getFab().setOnFloatingActionsMenuUpdateListener(new FloatingActionsMenu.OnFloatingActionsMenuUpdateListener() {
                @Override
                public void onMenuExpanded() {
                    showOuterFrame();
                }

                @Override
                public void onMenuCollapsed() {
                    hideOuterFrame();
                }
            });
            getFab().setIcon(R.drawable.ic_fab_new);
            getFab().setColorNormal(theme.accentColor());
            getFab().setColorPressed(theme.accentColorDark());
            getFab().getButton().setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fabPasteMode == BaseFileCab.PasteMode.ENABLED)
                        ((BaseFileCab) getCab()).paste();
                    else
                        ((FloatingActionsMenu) v.getParent()).toggle();
                }
            });
        }
    }

    private void showOuterFrame() {
        View outerFrame = findViewById(R.id.outerFrame);
        outerFrame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFab().collapse();
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                final View container = findViewById(R.id.container);
                int fabMargin = (int) getResources().getDimension(R.dimen.fab_margin);
                int halfButtonHeight = getFab().getButton().getMeasuredHeight() / 2;
                int cx;
                int cy;
                if (Utils.isRTL(this)) {
                    cx = fabMargin + halfButtonHeight;
                    cy = outerFrame.getMeasuredHeight() - (fabMargin + halfButtonHeight);
                } else {
                    cx = outerFrame.getMeasuredWidth() - fabMargin - halfButtonHeight;
                    cy = outerFrame.getMeasuredHeight() - fabMargin - halfButtonHeight;
                }

                int finalRadius = Math.max(container.getWidth(), container.getHeight());
                ViewAnimationUtils.createCircularReveal(outerFrame, cx, cy, 0, finalRadius).start();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
            outerFrame.setVisibility(View.VISIBLE);

        } else {
            outerFrame.setVisibility(View.VISIBLE);
        }
    }

    private void hideOuterFrame() {
        final View outerFrame = findViewById(R.id.outerFrame);
        outerFrame.setOnClickListener(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                final View container = findViewById(R.id.container);
                int fabMargin = (int) getResources().getDimension(R.dimen.fab_margin);
                int halfButtonHeight = getFab().getButton().getMeasuredHeight() / 2;
                int cx;
                int cy;
                if (Utils.isRTL(this)) {
                    cx = fabMargin + halfButtonHeight;
                    cy = outerFrame.getMeasuredHeight() - (fabMargin + halfButtonHeight);
                } else {
                    cx = outerFrame.getMeasuredWidth() - fabMargin - halfButtonHeight;
                    cy = outerFrame.getMeasuredHeight() - fabMargin - halfButtonHeight;
                }

                int finalRadius = Math.max(container.getWidth(), container.getHeight());
                Animator anim = ViewAnimationUtils.createCircularReveal(outerFrame, cx, cy, finalRadius, 0);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        outerFrame.setVisibility(View.GONE);
                    }
                });
                anim.start();
            } catch (IllegalStateException e) {
                e.printStackTrace();
                outerFrame.setVisibility(View.GONE);
            }
        } else {
            outerFrame.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        processIntent(intent, null);
    }

    private void checkRating() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("shown_rating_dialog", false)) {
            new MaterialDialog.Builder(MainActivity.this)
                    .title(R.string.rate)
                    .content(R.string.rate_desc)
                    .positiveText(R.string.sure)
                    .neutralText(R.string.later)
                    .negativeText(R.string.no_thanks)
                    .callback(new MaterialDialog.ButtonCallback() {
                        @Override
                        public void onPositive(MaterialDialog dialog) {
                            PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
                                    .edit().putBoolean("shown_rating_dialog", true).commit();
                            startActivity(new Intent(Intent.ACTION_VIEW)
                                    .setData(Uri.parse("market://details?id=com.afollestad.cabinet")));
                        }

                        @Override
                        public void onNegative(MaterialDialog dialog) {
                            PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
                                    .edit().putBoolean("shown_rating_dialog", true).commit();
                        }
                    }).show();
        }
    }

    @Override
    protected void processIntent(Intent intent, Bundle savedInstanceState) {
        super.processIntent(intent, savedInstanceState);
        pickMode = intent.getAction() != null && intent.getAction().equals(Intent.ACTION_GET_CONTENT);
        if (pickMode) {
            setCab(new PickerCab().setContext(this).start());
            switchDirectory(null, true);
        } else if (getRemoteSwitch() == null && savedInstanceState == null) {
            if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("shown_welcome", false)) {
                getFragmentManager().beginTransaction().replace(R.id.container, new WelcomeFragment()).commit();
            } else {
                if (!checkChangelog())
                    checkRating();
                switchDirectory(null, true);
            }
        }
    }

    @SuppressLint("CommitPrefEdits")
    public boolean checkChangelog() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final int currentVersion;
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentVersion = pInfo.versionCode;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (currentVersion != prefs.getInt("changelog_version", -1)) {
            final View customView;
            try {
                customView = LayoutInflater.from(this).inflate(R.layout.dialog_webview, null);
            } catch (InflateException e) {
                Toast.makeText(this, "This device does not support WebViews, cannot show the changelog.", Toast.LENGTH_LONG).show();
                return true;
            }
            MaterialDialog dialog = new MaterialDialog.Builder(this)
                    .title(R.string.changelog)
                    .customView(customView, false)
                    .positiveText(android.R.string.ok)
                    .callback(new MaterialDialog.ButtonCallback() {
                        @Override
                        public void onPositive(MaterialDialog dialog) {
                            prefs.edit().putInt("changelog_version", currentVersion).commit();
                        }
                    })
                    .build();
            WebView webView = (WebView) customView.findViewById(R.id.webview);
            webView.loadUrl("file:///android_asset/changelog.html");
            dialog.show();
            return true;
        }
        return false;
    }

    public void reloadNavDrawer(boolean open) {
        ((NavigationDrawerFragment) getFragmentManager().findFragmentByTag("NAV_DRAWER")).reload(open);
    }

    public void invalidateNavDrawerPadding(boolean cabStarted) {
        ((NavigationDrawerFragment) getFragmentManager().findFragmentByTag("NAV_DRAWER")).invalidatePadding(cabStarted);
    }

    public void reloadNavDrawer() {
        reloadNavDrawer(false);
    }

    public void switchDirectory(Pins.Item to) {
        File file = to.toFile(this);
        switchDirectory(file, file.isStorageDirectory(), false);
    }

    @Override
    public void switchDirectory(File to, boolean clearBackStack) {
        switchDirectory(to, clearBackStack, true);
    }

    public void switchDirectory(File to, boolean clearBackStack, boolean animate) {
        if (to == null) to = new LocalFile(this, Environment.getExternalStorageDirectory());
        if (clearBackStack)
            getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        FragmentTransaction trans = getFragmentManager().beginTransaction();
        // TODO temporarily disabled animations
//        if (animate && !clearBackStack)
//            trans.setCustomAnimations(R.anim.frag_enter, R.anim.frag_exit);
        trans.replace(R.id.container, DirectoryFragment.create(to));
        if (!clearBackStack) trans.addToBackStack(null);
        try {
            trans.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void search(File currentDir, String query) {
        getFragmentManager().beginTransaction().replace(R.id.container,
                DirectoryFragment.create(currentDir, query)).addToBackStack(null).commit();
    }

    public final void setStatus(int message, String replacement) {
        TextView status = (TextView) findViewById(R.id.status);
        if (message == 0) {
            status.setVisibility(View.GONE);
            invalidateToolbarColors(false);
        } else {
            status.setVisibility(View.VISIBLE);
            status.setText(getString(message, replacement));
            invalidateToolbarColors(false);
        }
    }

    public void invalidateToolbarColors(boolean forceCabGone) {
        LinearLayout toolbarDirectory = (LinearLayout) findViewById(R.id.toolbar_directory);

        BaseCab cab = getCab();
        int bgColor;
        if (cab != null && cab.isActive() && !forceCabGone) {
            bgColor = getResources().getColor(R.color.dark_theme_gray_darker);
        } else {
            bgColor = getThemeUtils().primaryColor();
        }
        toolbarDirectory.setBackgroundColor(bgColor);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            if (mCab != null && mCab.isActive()) {
                onBackPressed();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    /* Donation stuff via in app billing */

    @Override
    public void onBillingInitialized() {
    }

    @Override
    public void onProductPurchased(String productId, TransactionDetails transactionDetails) {
        mBP.consumePurchase(productId);
        Toast.makeText(this, R.string.thank_you, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBillingError(int errorCode, Throwable error) {
        if (errorCode != 110) {
            Toast.makeText(this, "Billing error: code = " + errorCode + ", error: " +
                    (error != null ? error.getMessage() : "?"), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPurchaseHistoryRestored() {
        /*
         * Called then purchase history was restored and the list of all owned PRODUCT ID's
         * was loaded from Google Play
         */
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mBP == null || !mBP.handleActivityResult(requestCode, resultCode, data))
            super.onActivityResult(requestCode, resultCode, data);
    }

    public void donate(int index) {
        mBP.purchase(this, "donation" + index);
    }

    @Override
    public void onDestroy() {
        if (mBP != null) mBP.release();
        super.onDestroy();
    }
}
