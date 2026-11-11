package com.yimu.lite;

/**
 * 页面 ↔ 骨架回调契约：页面类通过它请求切换页面、复用骨架的提示与生命周期挂载。
 * MainActivity 实现本接口并注入到各页面构造器。
 */
public interface PageContract {

    int PAGE_VOICE = 0;
    int PAGE_LIST = 1;
    int PAGE_SUMMARY = 2;
    int PAGE_DETAIL = 98;
    int PAGE_ADD = 99;

    /** 切换到指定页面（含导航高亮与 refresh 调用） */
    void goTo(int page);

    /** 打开指定账单的编辑页 */
    void openDetail(long billId);

    /** 最近一次打开的账单 id（DetailPage 回填用） */
    long currentDetailBillId();

    /** 请求录音权限；返回 true 表示已授权 */
    boolean ensureMicPermission();

    /** debug 钩子重放文本（仅 debuggable 构建生效） */
    void replayDemo(String text);
}
