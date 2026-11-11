package com.yimu.lite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 分类清单：一次从 Db 读出，供手输页与编辑页共用（含二级分类与选中定位）。 */
public final class Cats {

    public final List<JSONObject> expense = new ArrayList<>();
    public final List<JSONObject> income = new ArrayList<>();

    public static Cats load(Db db) throws Exception {
        Cats c = new Cats();
        JSONObject o = db.categories();
        JSONArray ex = o.getJSONArray("expense");
        for (int i = 0; i < ex.length(); i++) c.expense.add(ex.getJSONObject(i));
        JSONArray in = o.getJSONArray("income");
        for (int i = 0; i < in.length(); i++) c.income.add(in.getJSONObject(i));
        return c;
    }

    public List<JSONObject> of(String type) {
        return "income".equals(type) ? income : expense;
    }

    /** Spinner 展示用名称（一级 + 缩进二级） */
    public List<String> names(String type) {
        List<String> out = new ArrayList<>();
        for (JSONObject c : of(type)) {
            try {
                out.add(c.getString("icon") + " " + c.getString("name"));
                JSONArray subs = c.optJSONArray("sub_categories");
                if (subs != null) for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.getJSONObject(i);
                    out.add("　" + s.getString("icon") + " " + s.getString("name"));
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** 指定分类名在 names() 里的下标，找不到返回 0 */
    public int indexOf(String type, String selected) {
        if (selected == null) return 0;
        List<String> names = names(type);
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i).replaceFirst("^[\\s　]+", "");
            int sp = n.indexOf(' ');
            String name = (sp > 0 ? n.substring(sp + 1) : n).trim();
            if (name.equals(selected)) return i;
        }
        return 0;
    }

    /** 从 Spinner 文本反解分类名（去掉图标与缩进） */
    public static String nameOf(Object selectedItem) {
        if (selectedItem == null) return "";
        String s = selectedItem.toString().replaceFirst("^[\\s　]+", "");
        int sp = s.indexOf(' ');
        return (sp > 0 ? s.substring(sp + 1) : s).trim();
    }
}
