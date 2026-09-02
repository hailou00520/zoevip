package com.afusekt.lsp;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 绿茶 UI 精简：导航 Tab、主页收藏夹/广告/抽奖/推广等。
 */
public final class LvchaUiStrip {

    private static final String TAG = ZoeIds.TAG + ":LvchaUi";
    private static final String TAB_ADAPTER = "com.lvcha.main.adapter.TabFragmentPagerAdapter";
    private static final String CIRCLE_FRAGMENT = "com.lvcha.main.fragment.CircleFragment";

    private LvchaUiStrip() {
    }

    /** 旧 ViewPager 下标（0=导航,1=主页,2=我的）→ 新下标（0=主页,1=我的）。 */
    public static int remapMainPagerIndex(int oldIndex) {
        if (oldIndex <= 0) {
            return 0;
        }
        if (oldIndex == 1) {
            return 0;
        }
        return 1;
    }

    public static boolean isMainActivityPager(View view, ClassLoader cl) {
        if (view == null) {
            return false;
        }
        Context ctx = view.getContext();
        if (ctx == null) {
            return false;
        }
        int pagerId = resId(ctx, "id", "main_activity_view_pager");
        return pagerId != 0 && view.getId() == pagerId;
    }

    public static void hideNavigationPromo(ViewGroup root, ClassLoader cl) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        hideParentOf(root, ctx, "circle_view_pager");
        hideParentOf(root, ctx, "circle_hot_group");
    }

    public static void hideTopBanner(View view) {
        if (view == null) {
            return;
        }
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = 0;
            view.setLayoutParams(params);
        }
    }

    /** 主页：收藏夹、抽奖/推广、图片广告、地址栏等。 */
    public static void hideMainPagePromo(View root, ClassLoader cl) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        hideByName(root, ctx, "id", "circle_fragment_search_group");
        hideParentOf(root, ctx, "main_fragment_edit_group");
        hideByName(root, ctx, "id", "main_favorites_list");
        hideParentOf(root, ctx, "main_fragment_lottery");
        hideByName(root, ctx, "id", "main_fragment_active");
        hideByName(root, ctx, "id", "main_fragment_first_charge_group");
        hideByName(root, ctx, "id", "main_fragment_get_vip_now");
        hideByName(root, ctx, "id", "main_fragment_invite");
        adjustMainPageLayout(root);
    }

    /** 我的页：领取黄金会员抽奖横幅等。 */
    public static void hideMyPagePromo(View root, ClassLoader cl) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        hideByName(root, ctx, "id", "my_fragment_lottery");
        hideByName(root, ctx, "id", "my_fragment_lottery_go");
        hideByName(root, ctx, "id", "my_fragment_red");
    }

    /** 去掉地址栏重叠，下移 VPN 卡片与连接开关。 */
    private static void adjustMainPageLayout(View root) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        View search = findByName(root, ctx, "circle_fragment_search_group");
        if (search != null) {
            search.setVisibility(View.GONE);
            ViewGroup.LayoutParams searchParams = search.getLayoutParams();
            if (searchParams != null) {
                searchParams.height = 0;
                search.setLayoutParams(searchParams);
            }
        }

        View vpnCard = findByName(root, ctx, "main_fragment_start_button");
        if (vpnCard != null) {
            setMarginTop(vpnCard, dp(ctx, 180));
        }

        View switchGroup = findByName(root, ctx, "main_fragment_start_text_group");
        if (switchGroup != null && switchGroup.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margin) {
            int vertical = dp(ctx, 20);
            margin.topMargin = vertical;
            margin.bottomMargin = vertical;
            switchGroup.setLayoutParams(margin);
        }
    }

    /** 去掉底部「导航」Tab，ViewPager 仅保留主页 + 我的。 */
    public static void stripNavigationTab(Object mainActivity, ClassLoader cl) {
        if (mainActivity == null || cl == null || !(mainActivity instanceof Activity activity)) {
            return;
        }
        hideNavigationTabButton(activity);
        try {
            Field listField = mainActivity.getClass().getDeclaredField("B");
            listField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> fragments = (List<Object>) listField.get(mainActivity);
            if (fragments == null) {
                return;
            }
            if (fragments.size() == 3 && isCircleFragment(fragments.get(0), cl)) {
                ArrayList<Object> trimmed = new ArrayList<>(2);
                trimmed.add(fragments.get(1));
                trimmed.add(fragments.get(2));
                listField.set(mainActivity, trimmed);
                rebuildMainPager(mainActivity, cl, trimmed);
                log("navigation tab stripped (3->2 fragments)");
            } else if (fragments.size() == 2) {
                syncPagerHighlight(mainActivity, cl);
            }
        } catch (Throwable t) {
            log("stripNavigationTab failed: " + t.getMessage());
        }
    }

    private static void hideNavigationTabButton(Activity activity) {
        int loopGroupId = resId(activity, "id", "loop_button_group");
        if (loopGroupId == 0) {
            return;
        }
        View loopGroup = activity.findViewById(loopGroupId);
        if (loopGroup != null) {
            loopGroup.setVisibility(View.GONE);
            ViewGroup.LayoutParams params = loopGroup.getLayoutParams();
            if (params != null) {
                params.width = 0;
                params.height = 0;
                loopGroup.setLayoutParams(params);
            }
        }
    }

    private static boolean isCircleFragment(Object fragment, ClassLoader cl) {
        try {
            Class<?> circleCls = Class.forName(CIRCLE_FRAGMENT, false, cl);
            return circleCls.isInstance(fragment);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void rebuildMainPager(
            Object mainActivity, ClassLoader cl, List<Object> fragments) throws Exception {
        Field pagerField = mainActivity.getClass().getDeclaredField("A");
        pagerField.setAccessible(true);
        Object pager = pagerField.get(mainActivity);
        if (pager == null) {
            return;
        }

        Class<?> fragmentActivityCls = Class.forName(
                "androidx.fragment.app.FragmentActivity", false, cl);
        Method getFm = fragmentActivityCls.getMethod("getSupportFragmentManager");
        Object fm = getFm.invoke(mainActivity);

        Class<?> adapterCls = Class.forName(TAB_ADAPTER, false, cl);
        Class<?> fmCls = Class.forName("androidx.fragment.app.FragmentManager", false, cl);
        Class<?> listCls = Class.forName("java.util.List", false, cl);
        Constructor<?> ctor = adapterCls.getConstructor(fmCls, listCls);
        Object adapter = ctor.newInstance(fm, fragments);

        Class<?> pagerCls = Class.forName("androidx.viewpager.widget.ViewPager", false, cl);
        Class<?> pagerAdapterCls = Class.forName(
                "androidx.viewpager.widget.PagerAdapter", false, cl);
        pagerCls.getMethod("setAdapter", pagerAdapterCls).invoke(pager, adapter);
        pagerCls.getMethod("setOffscreenPageLimit", int.class).invoke(pager, 2);

        Method getCurrentItem = pagerCls.getMethod("getCurrentItem");
        int mapped = remapMainPagerIndex((Integer) getCurrentItem.invoke(pager));
        pagerCls.getMethod("setCurrentItem", int.class, boolean.class)
                .invoke(pager, mapped, false);
        applyTabHighlight(mainActivity, mapped);
    }

    private static void syncPagerHighlight(Object mainActivity, ClassLoader cl) throws Exception {
        Field pagerField = mainActivity.getClass().getDeclaredField("A");
        pagerField.setAccessible(true);
        Object pager = pagerField.get(mainActivity);
        if (pager == null) {
            return;
        }
        Class<?> pagerCls = Class.forName("androidx.viewpager.widget.ViewPager", false, cl);
        int current = (Integer) pagerCls.getMethod("getCurrentItem").invoke(pager);
        applyTabHighlight(mainActivity, current);
    }

    /** 双 Tab 模式下更新底部高亮（0=主页，1=我的）。 */
    public static void applyTabHighlight(Object mainActivity, int tabIndex) {
        if (!(mainActivity instanceof Activity activity)) {
            return;
        }
        try {
            Class<?> activityCls = mainActivity.getClass();
            ImageViews tabs = readTabViews(mainActivity, activityCls);
            if (tabs == null) {
                return;
            }
            int grep = resColorValue(activity, "lvcha_grep");
            int mainHigh = resColorValue(activity, "main_high");
            int mainIcon = resDrawable(activity, "main_tab_icon");
            int mainCheckedIcon = resDrawable(activity, "main_tab_checked_icon");
            int myIcon = resDrawable(activity, "my_tab_icon");
            int myCheckedIcon = resDrawable(activity, "my_tab_selected_icon");
            if (mainIcon == 0 || mainCheckedIcon == 0 || myIcon == 0 || myCheckedIcon == 0) {
                return;
            }

            if (tabIndex == 0) {
                tabs.mainIcon.setImageResource(mainCheckedIcon);
                if (mainHigh != 0) {
                    tabs.mainText.setTextColor(mainHigh);
                }
                tabs.myIcon.setImageResource(myIcon);
                if (grep != 0) {
                    tabs.myText.setTextColor(grep);
                }
            } else {
                tabs.mainIcon.setImageResource(mainIcon);
                if (grep != 0) {
                    tabs.mainText.setTextColor(grep);
                }
                tabs.myIcon.setImageResource(myCheckedIcon);
                if (mainHigh != 0) {
                    tabs.myText.setTextColor(mainHigh);
                }
            }
        } catch (Throwable t) {
            log("applyTabHighlight failed: " + t.getMessage());
        }
    }

    private static ImageViews readTabViews(Object mainActivity, Class<?> activityCls) throws Exception {
        Field mainIconField = activityCls.getDeclaredField("v");
        Field mainTextField = activityCls.getDeclaredField("x");
        Field myIconField = activityCls.getDeclaredField("t");
        Field myTextField = activityCls.getDeclaredField("u");
        mainIconField.setAccessible(true);
        mainTextField.setAccessible(true);
        myIconField.setAccessible(true);
        myTextField.setAccessible(true);
        Object mainIcon = mainIconField.get(mainActivity);
        Object mainText = mainTextField.get(mainActivity);
        Object myIcon = myIconField.get(mainActivity);
        Object myText = myTextField.get(mainActivity);
        if (!(mainIcon instanceof android.widget.ImageView)
                || !(mainText instanceof android.widget.TextView)
                || !(myIcon instanceof android.widget.ImageView)
                || !(myText instanceof android.widget.TextView)) {
            return null;
        }
        ImageViews views = new ImageViews();
        views.mainIcon = (android.widget.ImageView) mainIcon;
        views.mainText = (android.widget.TextView) mainText;
        views.myIcon = (android.widget.ImageView) myIcon;
        views.myText = (android.widget.TextView) myText;
        return views;
    }

    private static final class ImageViews {
        android.widget.ImageView mainIcon;
        android.widget.TextView mainText;
        android.widget.ImageView myIcon;
        android.widget.TextView myText;
    }

    public static void forceHideMainActiveBanner(Object mainFragment) {
        if (mainFragment == null) {
            return;
        }
        try {
            Field field = mainFragment.getClass().getDeclaredField("F");
            field.setAccessible(true);
            Object view = field.get(mainFragment);
            if (view instanceof View banner) {
                banner.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int resId(Context ctx, String type, String name) {
        return ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
    }

    private static int resDrawable(Context ctx, String name) {
        return resId(ctx, "drawable", name);
    }

    private static int resColorValue(Context ctx, String name) {
        int id = resId(ctx, "color", name);
        if (id == 0) {
            return 0;
        }
        return ctx.getResources().getColor(id, null);
    }

    private static void hideByName(View root, Context ctx, String type, String name) {
        int id = resId(ctx, type, name);
        if (id == 0) {
            return;
        }
        View view = root.findViewById(id);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    private static View findByName(View root, Context ctx, String name) {
        int id = resId(ctx, "id", name);
        if (id == 0) {
            return null;
        }
        return root.findViewById(id);
    }

    private static void setMarginTop(View view, int marginTop) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams margin) {
            margin.topMargin = marginTop;
            view.setLayoutParams(margin);
        }
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void hideParentOf(View root, Context ctx, String name) {
        int id = resId(ctx, "id", name);
        if (id == 0) {
            return;
        }
        View child = root.findViewById(id);
        if (child == null) {
            return;
        }
        View parent = child.getParent() instanceof View ? (View) child.getParent() : null;
        if (parent != null) {
            parent.setVisibility(View.GONE);
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
    }
}
