// Jev 助手（Mimo 版）v1.8 — QStory 可加载插件
// 功能：私聊消息 → 秒弹「快速意图+危险+情绪百分比+思考中」→ （可选）Mimo 返回后原地更新完整候选 → 点发送才发出
// v1.8：取消群聊分析，只保留单人私聊；移除群触发设置项
// v1.7.2：删除重复关系字段；字段详细说明；密钥不预填
// v1.7.1：总开关自绘状态行；输入框加高、文字16sp
// v1.6：全局设置表单化；测试API；第二轮总开关
// v1.5：快速决策取"最近"消息；发送后保留弹窗；本会话关系设置
// v1.4：候选按钮错发修复；情绪三分类
// v1.3：第二轮空白修复；快速决策读多条；第一轮显示危险与情绪百分比
// v1.2：两段式弹窗、本地快速意图判断、加载动画
// v1.1：弹窗全部用 Activity 创建、内置 Mimo 密钥/模型、诊断日志

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import android.graphics.Color;
import android.app.AlertDialog;
import android.content.res.Configuration;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

// ==================== 配置常量 ====================
String CONFIG_FILE = "config.json";
String SWITCH_NAME = "jev_switch";      // 会话级开关（悬浮窗菜单开启）
String AUTO_NAME   = "jev_auto";        // 会话级自动分析开关
String LLM_NAME    = "jev_llm_2nd";     // 全局：第二轮大模型总开关（关了只跑本地第一轮）
String GLOBAL_KEY  = "__global__";      // 全局设置使用的会话key占位
String MSG_DIR     = "msg";             // 历史消息目录
String SETTINGS_DIR = "settings";       // 会话级设置目录（每个会话单独的关系描述等）
String DIAG_FILE   = "jev_diag.log";    // 诊断日志

JSONObject config;                      // 主配置
HashMap<String, JSONArray> historyCache = new HashMap<>();   // 会话历史缓存
HashMap<String, MessageData> lastMsgCache = new HashMap<>(); // 各会话最近一条消息引用（用于回复）

// 弹窗管理：每个会话最多一个弹窗
HashMap<String, AlertDialog> pendingDialogs = new HashMap<>();      // 会话key -> 弹窗
HashMap<String, LinearLayout> pendingContainers = new HashMap<>();  // 会话key -> 弹窗内容容器
HashMap<String, android.os.Handler> pendingAnimHandlers = new HashMap<>(); // 会话key -> 加载动画Handler
HashMap<String, Integer> analyzeSeqs = new HashMap<>();             // 会话key -> 最新分析序号（防旧响应覆盖）

// ==================== 生命周期 ====================
void onLoad() {
    init();
}

void init() {
    loadConfig();
    File dir = new File(appPath + "/" + MSG_DIR);
    if (!dir.exists()) dir.mkdirs();
    File sdir = new File(appPath + "/" + SETTINGS_DIR);
    if (!sdir.exists()) sdir.mkdirs();
    addItem("开启本会话分析", "onMenuEnable");
    addItem("关闭本会话分析", "onMenuDisable");
    addItem("手动分析当前会话", "onMenuAnalyze");
    addItem("Jev 设置", "onMenuSettings");
    addItem("本会话关系设置", "onMenuRelation");
    addItem("Jev 诊断", "onMenuDiag");
    addItem("Jev 助手说明", "onMenuHelp");
    diag("onLoad OK 插件已加载，appPath=" + appPath + "，模型=" + config.optString("model", "") + "，密钥已填=" + (!config.optString("apiKey", "").isEmpty()));
    log("Jev 助手插件加载完成！");
}

void loadConfig() {
    String path = appPath + "/" + CONFIG_FILE;
    try {
        String text = readFileText(path);
        if (text == null || text.trim().isEmpty()) {
            JSONObject def = new JSONObject();
            def.put("baseUrl", "https://api.xiaomimimo.com/v1");
            def.put("apiKey", "sk-你的MiMo密钥");
            def.put("model", "mimo-v2.6-flash");
            def.put("relationship", "对方是和我关系密切的人；from=me 的是我发的，from=other 的是对方发的");
            def.put("groupTrigger", "at");
            def.put("defaultSessionOn", true);
            def.put("contextRounds", 8);
            def.put("maxTokens", 800);
            writeTextToFile(path, def.toString(4));
            text = readFileText(path);
        }
        config = new JSONObject(text);
        diag("配置加载成功");
    } catch (Exception e) {
        error(e);
        diag("配置加载失败: " + e);
        config = new JSONObject();
    }
}

// ==================== 消息处理 ====================
public void onMsg(MessageData msg) {
    if (msg.IsSend) return;              // 忽略自己发的
    String key = sessionKey(msg);
    if (key == null || key.isEmpty()) return;

    boolean sessionOn = getBoolean(SWITCH_NAME, key, config.optBoolean("defaultSessionOn", true));
    diag("onMsg key=" + key + " 群=" + msg.IsGroup + " 开关=" + sessionOn + " 内容=" + (msg.MessageContent != null ? msg.MessageContent.substring(0, Math.min(msg.MessageContent.length(), 20)) : "空"));

    if (!sessionOn) return;

    String content = msg.MessageContent;
    if (content == null || content.trim().isEmpty()) return;

    // v1.8 起取消群聊分析，只保留单人私聊模式
    if (msg.IsGroup) {
        diag("群聊消息已忽略（v1.8 仅私聊）");
        return;
    }

    appendHistory(key, "other", content);
    lastMsgCache.put(key, msg);

    // 自动分析
    if (getBoolean(AUTO_NAME, key, true)) {
        final String fKey = key;
        new Thread(new Runnable() {
            public void run() {
                analyze(fKey);
            }
        }).start();
    }
}

// ==================== 菜单回调 ====================
public void onMenuEnable(String groupUin, String uin, int chatType) {
    String key = sessionKeyFromMenu(groupUin, uin, chatType);
    if (key == null) return;
    putBoolean(SWITCH_NAME, key, true);
    putBoolean(AUTO_NAME, key, true);
    toast("已开启本会话 Jev 分析");
    diag("菜单: 开启 " + key);
}

public void onMenuDisable(String groupUin, String uin, int chatType) {
    String key = sessionKeyFromMenu(groupUin, uin, chatType);
    if (key == null) return;
    putBoolean(SWITCH_NAME, key, false);
    toast("已关闭本会话 Jev 分析");
    diag("菜单: 关闭 " + key);
}

public void onMenuAnalyze(String groupUin, String uin, int chatType) {
    String key = sessionKeyFromMenu(groupUin, uin, chatType);
    if (key == null) return;
    final String fKey = key;
    new Thread(new Runnable() {
        public void run() {
            analyze(fKey);
        }
    }).start();
}

public void onMenuDiag(String groupUin, String uin, int chatType) {
    String key = sessionKeyFromMenu(groupUin, uin, chatType);
    String content = readFileText(appPath + "/" + DIAG_FILE);
    if (content == null || content.trim().isEmpty()) content = "（还没有诊断日志，说明插件可能没有被 QStory 加载）";
    final String fContent = content.length() > 1800 ? content.substring(content.length() - 1800) : content;
    final android.app.Activity act = getActivity();
    if (act == null) { toast("无前台 Activity，无法弹窗。日志: " + content.substring(0, Math.min(200, content.length()))); return; }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                LinearLayout layout = new LinearLayout(act);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(dp(18), dp(14), dp(18), dp(10));
                TextView tv = new TextView(act);
                tv.setText(fContent);
                tv.setTextColor(Color.parseColor("#222222"));
                tv.setTextSize(12);
                layout.addView(tv);
                AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                b.setTitle("Jev 诊断日志");
                b.setView(layout);
                b.setPositiveButton("关闭", null);
                b.show();
            } catch (Exception e) { error(e); toast("诊断弹窗失败: " + e.getMessage()); }
        }
    });
}

public void onMenuHelp(String groupUin, String uin, int chatType) {
    toast("Jev 助手：读消息→秒弹意图/危险/情绪→思考中→出候选→点发送才发出。\n默认私聊自动分析；群聊需@机器人。配置在插件目录 config.json。");
}

/** 菜单：设置本会话的关系描述（每个聊天对象可单独设置，存入插件目录 settings/） */
public void onMenuRelation(String groupUin, String uin, int chatType) {
    final String key = sessionKeyFromMenu(groupUin, uin, chatType);
    if (key == null) { toast("无法识别当前会话"); return; }
    final String fKey = key;
    final String current = getSessionRelation(fKey);
    final android.app.Activity act = waitActivity();
    if (act == null) { toast("无前台界面，请在聊天窗口打开菜单"); return; }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                LinearLayout layout = new LinearLayout(act);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(dp(18), dp(10), dp(18), dp(4));

                TextView tip = new TextView(act);
                tip.setText("填写你与这个人的关系（例如：对方是我的暧昧对象 / 刚认识的朋友 / 同事）。Mimo 会结合关系来判断，不同聊天对象可填不同内容。留空则用 config.json 里的默认关系。");
                tip.setTextColor(Color.parseColor("#666666"));
                tip.setTextSize(13);
                tip.setPadding(0, 0, 0, dp(8));
                layout.addView(tip);

                final android.widget.EditText et = new android.widget.EditText(act);
                et.setText(current);
                et.setHint("如：对方是我的暧昧对象");
                et.setTextSize(15);
                layout.addView(et);

                AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                b.setTitle("设置本会话关系");
                b.setView(layout);
                b.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        String val = et.getText().toString().trim();
                        saveSessionRelation(fKey, val);
                        toast(val.isEmpty() ? "已清除本会话关系，改用默认" : "已保存本会话关系");
                        diag("菜单: 保存关系 key=" + fKey);
                    }
                });
                b.setNegativeButton("取消", null);
                b.show();
            } catch (Exception e) { error(e); toast("设置弹窗失败: " + e.getMessage()); }
        }
    });
}

/** 读取会话级关系描述；未单独设置时回退到 config.json 的全局 relationship */
String getSessionRelation(String key) {
    try {
        String txt = readFileText(appPath + "/" + SETTINGS_DIR + "/" + key + ".json");
        if (txt != null && !txt.trim().isEmpty()) {
            JSONObject o = new JSONObject(txt);
            String rel = o.optString("relationship", "");
            if (rel != null && !rel.trim().isEmpty()) return rel.trim();
        }
    } catch (Exception e) { error(e); }
    return config.optString("relationship", "");
}

/** 保存会话级关系描述（写入插件目录 settings/<key>.json） */
void saveSessionRelation(String key, String relation) {
    try {
        JSONObject o = new JSONObject();
        o.put("relationship", relation == null ? "" : relation);
        writeTextToFile(appPath + "/" + SETTINGS_DIR + "/" + key + ".json", o.toString());
    } catch (Exception e) { error(e); toast("保存失败: " + e.getMessage()); }
}

/** 第二轮大模型总开关（全局） */
boolean llmEnabled() {
    return getBoolean(LLM_NAME, GLOBAL_KEY, true);
}

/** 菜单：Jev 设置 —— 一个弹窗：顶部总开关 / 中间表单 / 底部「测试API」「保存」 */
public void onMenuSettings(String groupUin, String uin, int chatType) {
    final android.app.Activity act = waitActivity();
    if (act == null) { toast("无前台界面，请在聊天窗口打开菜单"); return; }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                int sh = act.getResources().getDisplayMetrics().heightPixels;

                ScrollView scroll = new ScrollView(act);
                LinearLayout form = new LinearLayout(act);
                form.setOrientation(LinearLayout.VERTICAL);
                form.setPadding(dp(18), dp(8), dp(18), dp(8));

                // ① 总开关区（自绘状态行，不依赖系统 Switch 样式）
                sectionTitle(act, form, "① 第二轮大模型（AI 生成候选回复）");
                final boolean[] llmOn = { llmEnabled() };
                final TextView llmState = new TextView(act);
                llmState.setTextSize(16);
                llmState.setPadding(dp(4), dp(10), dp(4), dp(10));
                applyLlmState(llmState, llmOn[0]);
                LinearLayout llmRow = new LinearLayout(act);
                llmRow.setOrientation(LinearLayout.HORIZONTAL);
                llmRow.setGravity(Gravity.CENTER_VERTICAL);
                llmRow.setClickable(true);
                llmRow.setOnClickListener(new android.view.View.OnClickListener() {
                    public void onClick(android.view.View v) {
                        llmOn[0] = !llmOn[0];
                        applyLlmState(llmState, llmOn[0]);
                    }
                });
                llmRow.addView(llmState);
                form.addView(llmRow);
                tip(act, form, "开启：秒弹本地判断后调用 API 生成 3 条候选回复；关闭：只显示本地快速判断，不调 API、不消耗 token。");

                // ② API 与模型区
                sectionTitle(act, form, "② API 与模型");
                final android.widget.EditText etUrl = makeField(act, "API 服务地址（默认 https://api.xiaomimimo.com/v1，一般不用改）", config.optString("baseUrl", "https://api.xiaomimimo.com/v1"), 2, "示例：https://api.xiaomimimo.com/v1", form);
                final android.widget.EditText etKey = makeField(act, "API 密钥（在 MiMo 控制台获取，sk- 开头；留空则不修改现有密钥）", "", 4, "示例：sk-xxxxxxxx", form);
                final android.widget.EditText etModel = makeField(act, "模型名称（默认 mimo-v2.6-flash，一般不用改）", config.optString("model", "mimo-v2.6-flash"), 2, "示例：mimo-v2.6-flash", form);
                final android.widget.EditText etRounds = makeField(act, "发给 AI 的历史消息条数（2~30，越大判断越准，但越慢、越费 token）", String.valueOf(config.optInt("contextRounds", 8)), 2, "示例：8", form);
                tip(act, form, "注：v1.8 起只分析单人私聊，群聊消息一律不处理。");

                scroll.addView(form);
                scroll.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int)(sh * 0.60)));

                // 外层：ScrollView + 底部按钮行
                LinearLayout root = new LinearLayout(act);
                root.setOrientation(LinearLayout.VERTICAL);
                root.addView(scroll);

                LinearLayout btnRow = new LinearLayout(act);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setPadding(dp(12), dp(4), dp(12), dp(8));

                Button btnTest = new Button(act);
                btnTest.setText("测试API");
                btnTest.setTextSize(15);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tp.setMargins(dp(4), 0, dp(4), 0);
                btnTest.setLayoutParams(tp);
                btnTest.setOnClickListener(new android.view.View.OnClickListener() {
                    public void onClick(android.view.View v) { runApiTest(); }
                });
                btnRow.addView(btnTest);

                Button btnSave = new Button(act);
                btnSave.setText("保存");
                btnSave.setTextSize(15);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                sp.setMargins(dp(4), 0, dp(4), 0);
                btnSave.setLayoutParams(sp);
                btnSave.setOnClickListener(new android.view.View.OnClickListener() {
                    public void onClick(android.view.View v) {
                        try {
                            putBoolean(LLM_NAME, GLOBAL_KEY, llmOn[0]);
                            config.put("baseUrl", etUrl.getText().toString().trim());
                            String newKey = etKey.getText().toString().trim();
                            if (!newKey.isEmpty()) config.put("apiKey", newKey); // 留空不改动现有密钥
                            config.put("model", etModel.getText().toString().trim());
                            int rounds = config.optInt("contextRounds", 8);
                            try { rounds = Integer.parseInt(etRounds.getText().toString().trim()); } catch (Exception e) { }
                            config.put("contextRounds", Math.max(2, Math.min(30, rounds)));
                            writeTextToFile(appPath + "/" + CONFIG_FILE, config.toString(4));
                            toast("已保存，立即生效（第二轮大模型" + (llmOn[0] ? "已开启" : "已关闭") + (newKey.isEmpty() ? "；密钥未改动" : "；密钥已更新") + "）");
                            diag("菜单: Jev设置已保存 llm=" + llmOn[0] + " keyChanged=" + (!newKey.isEmpty()));
                        } catch (Exception e) { error(e); toast("保存失败: " + e.getMessage()); }
                    }
                });
                btnRow.addView(btnSave);
                root.addView(btnRow);

                AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                b.setTitle("Jev 设置");
                b.setView(root);
                b.setNegativeButton("关闭", null);
                b.show();
            } catch (Exception e) { error(e); toast("设置弹窗失败: " + e.getMessage()); }
        }
    });
}

/** 设置弹窗里的分区小标题 */
void sectionTitle(android.app.Activity act, LinearLayout parent, String text) {
    TextView tv = new TextView(act);
    tv.setText(text);
    tv.setTextColor(Color.parseColor("#1565C0"));
    tv.setTextSize(16);
    tv.setTypeface(Typeface.DEFAULT_BOLD);
    tv.setPadding(0, dp(16), 0, dp(6));
    parent.addView(tv);
}

/** 设置弹窗里的灰色小提示 */
void tip(android.app.Activity act, LinearLayout parent, String text) {
    TextView tv = new TextView(act);
    tv.setText(text);
    tv.setTextColor(Color.parseColor("#777777"));
    tv.setTextSize(12);
    tv.setPadding(0, 0, 0, dp(6));
    parent.addView(tv);
}

/** 表单里的一行：标签 + 多行输入框（高度/文字加大，带占位提示） */
android.widget.EditText makeField(android.app.Activity act, String label, String value, int minLines, String hint, LinearLayout parent) {
    TextView tv = new TextView(act);
    tv.setText(label);
    tv.setTextColor(Color.parseColor("#444444"));
    tv.setTextSize(13);
    tv.setPadding(0, dp(12), 0, dp(3));
    parent.addView(tv);

    android.widget.EditText et = new android.widget.EditText(act);
    et.setText(value == null ? "" : value);
    et.setTextSize(16);
    if (hint != null && !hint.isEmpty()) et.setHint(hint);
    et.setSingleLine(false);
    et.setMinLines(minLines);
    et.setGravity(Gravity.TOP | Gravity.LEFT);
    parent.addView(et);
    return et;
}

/** 总开关状态行的文字/颜色 */
void applyLlmState(TextView tv, boolean on) {
    if (on) {
        tv.setText("第二轮大模型：开启 ✓（秒弹判断后调用 API 出候选回复）");
        tv.setTextColor(Color.parseColor("#2E7D32"));
    } else {
        tv.setText("第二轮大模型：关闭 ✗（只显示本地判断，不调 API）");
        tv.setTextColor(Color.parseColor("#999999"));
    }
}

/** 测试当前 API 配置是否可用（后台线程，弹窗显示结果） */
void runApiTest() {
    toast("正在测试API连通性…");
    new Thread(new Runnable() {
        public void run() {
            String result;
            try {
                long t0 = System.currentTimeMillis();
                JSONObject payload = new JSONObject();
                payload.put("model", config.optString("model", "mimo-v2.6-flash"));
                JSONArray msgs = new JSONArray();
                JSONObject m = new JSONObject();
                m.put("role", "user");
                m.put("content", "ping，请只回复OK两个字");
                msgs.put(m);
                payload.put("messages", msgs);
                payload.put("max_tokens", 10);
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("Authorization", "Bearer " + config.optString("apiKey", ""));
                headers.put("Timeout", "30000");
                String url = config.optString("baseUrl", "https://api.xiaomimimo.com/v1") + "/chat/completions";
                String resp = httpPostJson(url, headers, payload.toString());
                long cost = System.currentTimeMillis() - t0;
                if (resp == null || resp.trim().isEmpty()) {
                    result = "测试失败：无响应（检查网络/API地址/密钥）";
                } else {
                    JSONObject o = new JSONObject(resp);
                    if (o.has("error")) {
                        result = "测试失败：API返回错误 - " + o.optJSONObject("error").optString("message", "未知");
                    } else {
                        String content = o.optJSONArray("choices").optJSONObject(0).optJSONObject("message").optString("content", "");
                        result = "测试成功！耗时 " + cost + "ms\n模型：" + o.optJSONObject("model") + "\n回复：" + content;
                    }
                }
            } catch (Exception e) {
                result = "测试异常: " + e.getMessage();
            }
            final String fResult = result;
            final android.app.Activity act = getActivity();
            if (act != null) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                            b.setTitle("API 测试结果");
                            b.setMessage(fResult);
                            b.setPositiveButton("关闭", null);
                            b.show();
                        } catch (Exception e) { toast(fResult); }
                    }
                });
            } else {
                toast(fResult);
            }
            diag("API测试: " + fResult);
        }
    }).start();
}

String sessionKeyFromMenu(String groupUin, String uin, int chatType) {
    String key = (chatType == 2) ? "group-" + groupUin : "friend-" + (uin != null ? uin : "");
    if (key.endsWith("-")) return null;
    return key;
}

// ==================== 本地快速决策（毫秒级，基于最近多条本地消息） ====================
/** 快速意图：输入对方最近多条消息（联合判断，多点命中更准） */
String quickJudgeIntent(String text) {
    if (text == null || text.trim().isEmpty()) return "对方发来一条消息";
    String t = text.trim();
    int anger = countHits(t, new String[]{"气死", "烦", "呵呵", "无语", "算了", "随便你", "你走", "别理我", "不想说", "生气", "失望", "哼", "你厉害", "有病", "滚", "受够了", "别烦我"});
    int test  = countHits(t, new String[]{"还记得", "记不记得", "你记得", "知道吗", "在乎吗", "在不在乎", "有没有想我", "想我吗", "是不是忘了", "你猜", "还知道", "还记不记得", "我重要吗"});
    int sorry = countHits(t, new String[]{"对不起", "抱歉", "我错了", "是我的错", "原谅", "别生气", "我不好", "都是我的错", "别不理我"});
    int cute  = countHits(t, new String[]{"想你", "爱你", "抱抱", "亲亲", "么么", "喜欢你", "夸夸", "乖", "好想你", "想你了", "在干嘛呀", "嘤嘤"});
    int ask   = countHits(t, new String[]{"怎么办", "该不该", "好不好", "行不行", "怎么", "为什么", "帮帮我", "建议", "要不要", "可不可以", "你觉得"});
    int need  = countHits(t, new String[]{"帮我", "需要", "想要", "记得买", "别忘了", "明天", "几点", "发我", "给我", "弄一下", "转我", "借我"});
    int cold  = countHits(t, new String[]{"嗯", "哦", "好吧", "行吧", "知道了", "随便", "没事", "哦哦"});
    int funny = countHits(t, new String[]{"哈哈", "笑死", "好玩", "有趣", "开玩笑", "整活", "绷不住", "搞笑", "乐死"});
    int sad   = countHits(t, new String[]{"难过", "伤心", "哭", "委屈", "累", "失眠", "睡不着", "没意思", "烦死", "emo", "破防", "焦虑", "失落", "心酸", "想哭", "不舒服", "难受", "崩溃", "叹气", "头疼", "发烧", "生病", "好痛", "心疼"});
    int jeal  = countHits(t, new String[]{"吃醋", "那个人", "谁啊", "男的女的", "挺热闹", "关系不错", "聊得挺好", "她是谁"});
    int greet = countHits(t, new String[]{"在干嘛", "在吗", "在不在", "忙吗", "干嘛呢", "睡了吗", "起床没", "有空吗"});

    int[] scores = {anger, test, sorry, cute, ask, need, cold, funny, sad, jeal, greet};
    String[] names = {"生气/不满", "试探你是否在乎", "道歉/认错", "撒娇/示好", "疑问/求建议", "有事要办/有需求", "敷衍/冷淡", "玩笑/轻松", "难过/低落", "吃醋", "问候/找你"};

    int best = 0;
    int bestScore = 0;
    for (int i = 0; i < scores.length; i++) {
        if (scores[i] > bestScore) { bestScore = scores[i]; best = i; }
    }
    if (bestScore > 0) return names[best];
    if (t.length() <= 4) return "短消息（语气不明）";
    return "正常交流/闲聊";
}

int countHits(String text, String[] kws) {
    int n = 0;
    for (int i = 0; i < kws.length; i++) {
        if (text.contains(kws[i])) n++;
    }
    return n;
}

/** 快速危险估计（1-9，最终以 Mimo 为准） */
int quickRisk(String text) {
    if (text == null) return 1;
    int r = 1;
    String[] hot = {"气死", "分手", "离婚", "滚", "绝交", "拉黑", "别联系", "受够了", "不想过了", "冷战", "不理你", "随便你"};
    String[] warm = {"想你", "爱你", "抱抱", "么么", "乖", "喜欢", "好想"};
    for (int i = 0; i < hot.length; i++) if (text.contains(hot[i])) r += 2;
    for (int i = 0; i < warm.length; i++) if (text.contains(warm[i])) r -= 1;
    if (r > 7) r = 7;
    if (r < 1) r = 1;
    return r;
}

/** 快速情绪百分比：返回 {开心, 难过, 生气}，和为100 */
int[] quickEmotion(String text) {
    int[] base = {0, 0, 0};
    if (text == null || text.trim().isEmpty()) return base;
    String t = text;
    base[0] = countHits(t, new String[]{"开心", "高兴", "哈哈", "嘻嘻", "嘿嘿", "棒", "好耶", "爱你", "想你", "喜欢", "抱抱", "么么", "夸夸", "好笑", "好玩", "快乐", "欣慰"});
    base[1] = countHits(t, new String[]{"难过", "伤心", "哭", "委屈", "累", "失眠", "破防", "emo", "焦虑", "没意思", "失落", "心酸", "难受", "崩溃", "叹气", "想哭", "不舒服", "头疼", "胃疼", "发烧", "生病", "好痛", "心疼"});
    base[2] = countHits(t, new String[]{"气死", "烦", "无语", "呵呵", "滚", "有病", "生气", "失望", "受够了", "别理", "火大", "暴躁", "恶心", "别烦我", "懒得理", "服了"});

    int total = base[0] + base[1] + base[2];
    int[] out = new int[3];
    if (total == 0) {
        out[0] = 34; out[1] = 33; out[2] = 33; // 无明显倾向，给接近均衡的值
        return out;
    }
    for (int i = 0; i < 3; i++) {
        out[i] = (int) Math.round(base[i] * 100.0 / total);
    }
    // 修正四舍五入误差，保证和为100
    int sum = out[0] + out[1] + out[2];
    if (sum != 100) {
        int maxIdx = 0;
        for (int i = 1; i < 3; i++) if (out[i] > out[maxIdx]) maxIdx = i;
        out[maxIdx] += (100 - sum);
    }
    return out;
}

// ==================== 分析核心（两段式） ====================
void analyze(String key) {
    try {
        diag("analyze 开始 key=" + key);
        JSONArray history = getHistory(key);
        if (history == null || history.length() == 0) {
            diag("analyze: 无历史消息");
            showSimpleDialog(key, "还没有消息可分析", "本会话还没有记录到消息。私聊对方发消息即可；群聊需开启分析并 @ 机器人。");
            return;
        }
        int rounds = config.optInt("contextRounds", 8);

        // 收集：最近 3 条对方消息（联合判断）+ 最近 1 条我方消息 + 全量 convo 给 Mimo
        StringBuilder convo = new StringBuilder();
        java.util.ArrayList<String> othersAll = new java.util.ArrayList<>();
        java.util.ArrayList<String> meAll = new java.util.ArrayList<>();
        String lastOther = "";
        for (int i = Math.max(0, history.length() - rounds); i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            String side = m.optString("side", "other");
            String txt = m.optString("text", "");
            if (txt.isEmpty()) continue;
            convo.append(side.equals("me") ? "我：" : "对方：").append(txt).append("\n");
            if (side.equals("other")) {
                othersAll.add(txt);
                lastOther = txt;
            } else {
                meAll.add(txt);
            }
        }
        // 取"最近"的 3 条对方 + 最近 1 条自己（从尾部取，最新消息优先）
        StringBuilder othersBuf = new StringBuilder();
        for (int i = Math.max(0, othersAll.size() - 3); i < othersAll.size(); i++) {
            othersBuf.append(othersAll.get(i)).append("\n");
        }
        StringBuilder meBuf = new StringBuilder();
        for (int i = Math.max(0, meAll.size() - 1); i < meAll.size(); i++) {
            meBuf.append(meAll.get(i)).append("\n");
        }
        String othersText = othersBuf.toString().trim();
        if (othersText.isEmpty()) othersText = lastOther;

        // 阶段1：本地快速决策（意图/危险/情绪），秒弹
        final int seq = nextSeq(key);
        final String quickIntent = quickJudgeIntent(othersText);
        final int quickRisk = quickRisk(othersText);
        final int[] emo = quickEmotion(othersText);
        diag("analyze: 快速意图=" + quickIntent + " 危险=" + quickRisk + " 情绪=" + emo[0] + "/" + emo[1] + "/" + emo[2]);
        showQuickDialog(key, seq, quickIntent, quickRisk, emo, lastOther);

        // v1.6：第二轮大模型总开关，关闭时只跑本地第一轮，不调用API
        if (!llmEnabled()) {
            diag("第二轮大模型已关闭，仅完成本地快速判断");
            return;
        }

        // 阶段2：异步调 Mimo，返回后原地更新
        JSONObject payload = buildPayload(key, convo.toString());
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Authorization", "Bearer " + config.optString("apiKey", ""));
        headers.put("Timeout", "60000");
        String url = config.optString("baseUrl", "https://api.xiaomimimo.com/v1") + "/chat/completions";
        diag("analyze: 发起 HTTP 请求 " + url + " 模型=" + config.optString("model", ""));
        log("请求: " + payload.toString());
        String respText = httpPostJson(url, headers, payload.toString());
        diag("analyze: HTTP 返回 长度=" + (respText == null ? 0 : respText.length()) + " 开头=" + (respText == null ? "null" : respText.substring(0, Math.min(respText.length(), 120))));
        log("响应: " + respText);

        if (respText == null || respText.isEmpty()) {
            diag("analyze: 网络无响应");
            updateDialogWithError(key, seq, "请求失败", "网络无响应，请检查网络与 API 地址、密钥。");
            return;
        }
        JSONObject resp = new JSONObject(respText);
        if (resp.has("error")) {
            String errMsg = resp.optJSONObject("error").optString("message", "未知错误");
            diag("analyze: API 错误 " + errMsg);
            updateDialogWithError(key, seq, "API 错误", errMsg);
            return;
        }
        String content = resp.optJSONArray("choices").optJSONObject(0).optJSONObject("message").optString("content", "");
        JSONObject analysis = extractJson(content);
        if (analysis == null) {
            diag("analyze: 返回内容无法解析为 JSON");
            updateDialogWithError(key, seq, "解析失败", "Mimo 返回内容无法解析为 JSON，请查看日志。");
            return;
        }

        String intent = analysis.optString("intent", "未知");
        int risk = analysis.optInt("risk_level", 1);
        String action = analysis.optString("suggest_action", "");
        JSONArray candidates = analysis.optJSONArray("candidates");
        diag("analyze: 解析成功 intent=" + intent + " risk=" + risk + " 候选=" + (candidates == null ? 0 : candidates.length()));
        updateAnalysisDialog(key, seq, intent, risk, action, candidates);
    } catch (Exception e) {
        error(e);
        diag("analyze 异常: " + e);
        updateDialogWithError(key, nextSeq(key), "分析异常", "异常: " + e.getMessage());
    }
}

/** 分配/递增会话分析序号 */
int nextSeq(String key) {
    Integer cur = analyzeSeqs.get(key);
    int next = (cur == null ? 0 : cur.intValue()) + 1;
    analyzeSeqs.put(key, next);
    return next;
}

JSONObject buildPayload(String key, String convo) throws Exception {
    String system = "你是即时通讯回复助手。分析最近对话，只输出一个 JSON 对象，禁止输出任何多余文字：\n" +
        "{\"intent\":\"对方真实意图(简短中文)\",\"confidence\":0-100,\"risk_level\":1-9(1安全,9关系决裂),\"" +
        "should_reply\":true或false,\"suggest_action\":\"建议动作:翻聊天记录/先道歉/给承诺/解释清楚/接住情绪/少说两句/定个安排/转移话题\"," +
        "\"candidates\":[恰好3条候选回复按适配度从高到低,每条{\"content\":\"口语自然不超过40字\",\"score\":0-100}]}\n" +
        "规则：对方试探你是否记得/在乎→intent判为确认你在不在乎,action优先翻聊天记录；对方已接受道歉→risk低,should_reply可为false；三条候选策略要有区别（如一句最稳、一句走心、一句幽默）。";
    String user = "关系说明：" + getSessionRelation(key) + "\n\n最近对话：\n" + convo + "\n\n请分析并只输出 JSON。";

    JSONArray messages = new JSONArray();
    JSONObject sys = new JSONObject();
    sys.put("role", "system");
    sys.put("content", system);
    messages.put(sys);
    JSONObject usr = new JSONObject();
    usr.put("role", "user");
    usr.put("content", user);
    messages.put(usr);

    JSONObject payload = new JSONObject();
    payload.put("model", config.optString("model", "mimo-v2.6-flash"));
    payload.put("messages", messages);
    payload.put("temperature", 0.5);
    payload.put("max_tokens", config.optInt("maxTokens", 800));
    return payload;
}

/** 容错提取 JSON 对象（去 ```json 围栏 / 前后杂文） */
JSONObject extractJson(String content) {
    if (content == null) return null;
    String text = content.trim();
    try { return new JSONObject(text); } catch (Exception e) { }
    java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{[\\s\\S]*\\}");
    java.util.regex.Matcher m = p.matcher(text);
    if (m.find()) {
        try { return new JSONObject(m.group()); } catch (Exception e2) { }
    }
    return null;
}

// ==================== 回复发送 ====================
void sendReplyFor(final String key, final String text) {
    try {
        MessageData m = lastMsgCache.get(key);
        if (m == null) {
            toast("会话消息上下文丢失，请重试");
            return;
        }
        if (m.IsGroup) {
            sendReply(m.GroupUin, m, text);
        } else {
            String to = (m.PeerUin != null && !m.PeerUin.isEmpty()) ? m.PeerUin : m.UserUin;
            sendMsg("", to, text);
        }
        toast("已发送");
        diag("已发送候选回复 key=" + key);
        appendHistory(key, "me", text);
        // v1.5：发送后保留弹窗（用户可在弹窗里继续查看/复制其他候选），
        // 对应按钮已变为"✓已发送"并禁用，防止重复发送；需要关闭时点弹窗"关闭"。
    } catch (Exception e) {
        error(e);
        toast("发送失败: " + e.getMessage());
        diag("发送失败: " + e);
    }
}

// ==================== 历史消息 ====================
String sessionKey(MessageData msg) {
    if (msg.IsGroup) return "group-" + msg.GroupUin;
    String peer = (msg.PeerUin != null && !msg.PeerUin.isEmpty()) ? msg.PeerUin : msg.UserUin;
    if (peer == null || peer.isEmpty()) return null;
    return "friend-" + peer;
}

void appendHistory(String key, String side, String text) {
    try {
        JSONArray arr = getHistory(key);
        JSONObject m = new JSONObject();
        m.put("side", side);
        m.put("text", text);
        m.put("time", System.currentTimeMillis());
        arr.put(m);
        if (arr.length() > 100) {
            JSONArray trimmed = new JSONArray();
            for (int i = arr.length() - 100; i < arr.length(); i++) {
                trimmed.put(arr.optJSONObject(i));
            }
            arr = trimmed;
            historyCache.put(key, arr);
        }
        saveHistory(key, arr);
    } catch (Exception e) {
        error(e);
    }
}

JSONArray getHistory(String key) {
    JSONArray arr = historyCache.get(key);
    if (arr == null) {
        arr = loadHistory(key);
        historyCache.put(key, arr);
    }
    return arr;
}

JSONArray loadHistory(String key) {
    try {
        String txt = readFileText(appPath + "/" + MSG_DIR + "/" + key + ".json");
        if (txt != null && !txt.trim().isEmpty()) return new JSONArray(txt);
    } catch (Exception e) { error(e); }
    return new JSONArray();
}

void saveHistory(String key, JSONArray arr) {
    try {
        writeTextToFile(appPath + "/" + MSG_DIR + "/" + key + ".json", arr.toString());
    } catch (Exception e) { error(e); }
}

// ==================== 弹窗（两段式） ====================
/** 等待并获取 Activity（最多约 3 秒，QQ 前台时立即返回） */
android.app.Activity waitActivity() {
    final android.app.Activity[] act = {getActivity()};
    if (act[0] != null) return act[0];
    final Object lock = new Object();
    final boolean[] done = {false};
    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable poll = new Runnable() {
        public void run() {
            act[0] = getActivity();
            if (act[0] != null || done[0]) {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            } else {
                handler.postDelayed(this, 500);
            }
        }
    };
    handler.postDelayed(poll, 500);
    synchronized (lock) {
        try { lock.wait(3000); } catch (InterruptedException e) { }
    }
    return act[0];
}

/** 必须在 UI 线程调用：关闭某会话现有弹窗（若有） */
void dismissDialogNow(String key) {
    AlertDialog dlg = pendingDialogs.get(key);
    if (dlg != null && dlg.isShowing()) {
        dlg.dismiss(); // 触发 onDismiss 清理
    }
}

/** 阶段1：秒弹「快速意图 + 危险 + 情绪 + 正在思考…」 */
void showQuickDialog(final String key, final int seq, final String intent, final int risk, final int[] emo, final String msgText) {
    final android.app.Activity act = waitActivity();
    if (act == null) {
        diag("快速弹窗被跳过（无 Activity）");
        return;
    }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                dismissDialogNow(key);   // 防堆叠：先关掉同会话旧弹窗
                stopLoadingAnim(key);
                LinearLayout container = new LinearLayout(act);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(dp(18), dp(14), dp(18), dp(6));

                TextView title = new TextView(act);
                title.setText("对方意图：" + intent);
                title.setTextColor(riskColor(risk));
                title.setTextSize(17);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                container.addView(title);

                TextView meta = new TextView(act);
                meta.setText("危险 " + risk + "/9（预估） · 情绪 开心" + emo[0] + "% 难过" + emo[1] + "% 生气" + emo[2] + "%（基于最近几条消息）");
                meta.setTextColor(Color.parseColor("#555555"));
                meta.setTextSize(13);
                meta.setPadding(0, dp(6), 0, dp(6));
                container.addView(meta);

                if (msgText != null && !msgText.isEmpty()) {
                    TextView msgView = new TextView(act);
                    msgView.setText("对方说：" + msgText);
                    msgView.setTextColor(Color.parseColor("#666666"));
                    msgView.setTextSize(13);
                    msgView.setPadding(0, dp(2), 0, dp(6));
                    container.addView(msgView);
                }

                final TextView loading = new TextView(act);
                loading.setText("正在思考回复内容…");
                loading.setTextColor(Color.parseColor("#888888"));
                loading.setTextSize(15);
                loading.setPadding(0, dp(8), 0, dp(10));
                container.addView(loading);

                // 加载动画
                final android.os.Handler anim = new android.os.Handler(android.os.Looper.getMainLooper());
                final int[] dots = {0};
                Runnable tick = new Runnable() {
                    public void run() {
                        dots[0] = (dots[0] + 1) % 4;
                        StringBuilder s = new StringBuilder("正在思考回复内容");
                        for (int i = 0; i < dots[0]; i++) s.append(".");
                        loading.setText(s.toString());
                        anim.postDelayed(this, 400);
                    }
                };
                anim.postDelayed(tick, 400);
                pendingAnimHandlers.put(key, anim);

                AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                b.setTitle("Jev 助手");
                b.setView(container);
                b.setNegativeButton("关闭", null);
                b.setCancelable(true);
                AlertDialog dlg = b.create();
                dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    public void onDismiss(android.content.DialogInterface d) {
                        stopLoadingAnim(key);
                        pendingDialogs.remove(key);
                        pendingContainers.remove(key);
                    }
                });
                pendingDialogs.put(key, dlg);
                pendingContainers.put(key, container);
                dlg.show();
                diag("快速弹窗已显示 seq=" + seq + " intent=" + intent + " risk=" + risk);
            } catch (Exception e) { error(e); diag("快速弹窗异常: " + e); }
        }
    });
}

/** 阶段2：Mimo 返回后，原地更新成完整候选（失败自动重建，不空白） */
void updateAnalysisDialog(final String key, final int seq, final String intent, final int risk, final String action, final JSONArray candidates) {
    final android.app.Activity act = waitActivity();
    if (act == null) {
        diag("更新弹窗被跳过（无 Activity）");
        return;
    }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                // 旧响应（非最新序号）直接忽略，防止覆盖新弹窗
                Integer cur = analyzeSeqs.get(key);
                if (cur == null || cur.intValue() != seq) {
                    diag("忽略旧响应 seq=" + seq + " 当前=" + cur);
                    return;
                }
                AlertDialog dlg = pendingDialogs.get(key);
                LinearLayout container = pendingContainers.get(key);
                if (dlg == null || container == null || !dlg.isShowing()) {
                    diag("快速弹窗已不存在，直接弹完整候选");
                    showAnalysisDialog(key, intent, risk, action, candidates);
                    return;
                }
                stopLoadingAnim(key);
                container.removeAllViews();
                LinearLayout full = buildAnalysisView(act, key, intent, risk, action, candidates);
                while (full.getChildCount() > 0) {
                    container.addView(full.getChildAt(0));
                }
                container.requestLayout();
                diag("候选弹窗已原地更新 seq=" + seq);
            } catch (Exception e) {
                error(e);
                diag("原地更新异常，改重建弹窗: " + e);
                // 原地更新失败 → 重建完整弹窗，避免白屏
                showAnalysisDialog(key, intent, risk, action, candidates);
            }
        }
    });
}

/** 出错时把弹窗更新为错误信息（同样带序号防旧响应） */
void updateDialogWithError(final String key, final int seq, final String title, final String message) {
    final android.app.Activity act = waitActivity();
    if (act == null) { diag("错误弹窗被跳过（无 Activity）: " + title + " " + message); toast(title + ": " + message); return; }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                Integer cur = analyzeSeqs.get(key);
                if (cur == null || cur.intValue() != seq) {
                    diag("忽略旧错误响应 seq=" + seq + " 当前=" + cur);
                    return;
                }
                AlertDialog dlg = pendingDialogs.get(key);
                LinearLayout container = pendingContainers.get(key);
                if (dlg == null || container == null || !dlg.isShowing()) {
                    showSimpleDialog(key, title, message);
                    return;
                }
                stopLoadingAnim(key);
                container.removeAllViews();
                TextView tv = new TextView(act);
                tv.setText(message);
                tv.setTextColor(Color.parseColor("#D32F2F"));
                tv.setTextSize(15);
                tv.setPadding(0, dp(6), 0, dp(6));
                container.addView(tv);
                diag("错误信息已原地更新 seq=" + seq);
            } catch (Exception e) { error(e); diag("错误更新异常: " + e); }
        }
    });
}

/** 构建完整候选视图（供原地更新与兜底弹窗复用） */
LinearLayout buildAnalysisView(final android.app.Activity act, final String key, final String intent, final int risk, final String action, final JSONArray candidates) {
    LinearLayout layout = new LinearLayout(act);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dp(18), dp(14), dp(18), dp(6));

    TextView title = new TextView(act);
    title.setText("意图：" + intent + "   危险 " + risk + "/9");
    title.setTextColor(riskColor(risk));
    title.setTextSize(17);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    layout.addView(title);

    if (action != null && !action.isEmpty()) {
        TextView actView = new TextView(act);
        actView.setText("建议动作：" + action);
        actView.setTextColor(Color.parseColor("#666666"));
        actView.setTextSize(13);
        actView.setPadding(0, dp(4), 0, dp(8));
        layout.addView(actView);
    }

    if (candidates == null || candidates.length() == 0) {
        TextView hint = new TextView(act);
        hint.setText("（未生成候选回复）");
        hint.setTextColor(Color.parseColor("#999999"));
        hint.setTextSize(14);
        hint.setPadding(0, dp(8), 0, dp(8));
        layout.addView(hint);
    } else {
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null) continue;
            final String text = c.optString("content", "").trim();
            if (text.isEmpty()) continue;
            layout.addView(makeCandidateRow(act, i + 1, text, key));
        }
    }
    return layout;
}

/**
 * 候选回复行。
 * 注意：文本通过 setTag 存入按钮、点击时用 v.getTag() 取回，
 * 不在匿名类里直接引用循环变量，避免脚本引擎闭包陷阱导致"所有按钮都发最后一条"。
 */
LinearLayout makeCandidateRow(final android.app.Activity act, final int index, final String text, final String key) {
    LinearLayout row = new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(6), 0, dp(2));

    TextView label = new TextView(act);
    label.setText(index + ".");
    label.setTextColor(Color.parseColor("#333333"));
    label.setTextSize(15);
    label.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(label);

    TextView body = new TextView(act);
    body.setText(text);
    body.setTextColor(Color.parseColor("#222222"));
    body.setTextSize(15);
    body.setPadding(dp(8), 0, dp(8), 0);
    body.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    row.addView(body);

    Button send = new Button(act);
    send.setText("发送");
    send.setTextSize(13);
    send.setTag(text); // 当次候选文本存入按钮，点击时取回（绕开循环闭包）
    send.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            try {
                // v1.5：点击后保留弹窗（不做自动关闭），按钮变"✓已发送"防重复点击
                if (v instanceof Button) {
                    Button b = (Button) v;
                    b.setText("✓已发送");
                    b.setEnabled(false);
                    b.setAlpha(0.6f);
                }
                String t = (String) v.getTag();
                sendReplyFor(key, t);
            } catch (Exception e) { error(e); }
        }
    });
    row.addView(send);
    return row;
}

/** 兜底：直接弹完整候选弹窗（快速弹窗已被关闭等场景），同样注册以便发送后自动关闭 */
void showAnalysisDialog(String key, String intent, int risk, String action, JSONArray candidates) {
    final String fKey = key;
    final String fIntent = intent;
    final int fRisk = risk;
    final String fAction = action;
    final JSONArray fCandidates = candidates;
    final android.app.Activity act = waitActivity();
    if (act == null) {
        diag("候选弹窗被跳过（无 Activity）: " + intent);
        toast("分析完成 意图:" + intent + " 危险:" + fRisk + "/9（无界面，请查看日志）");
        return;
    }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                dismissDialogNow(fKey); // 防堆叠
                AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                b.setTitle("Jev 助手");
                b.setView(buildAnalysisView(act, fKey, fIntent, fRisk, fAction, fCandidates));
                b.setNegativeButton("关闭", null);
                b.setCancelable(true);
                AlertDialog dlg = b.create();
                dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    public void onDismiss(android.content.DialogInterface d) {
                        pendingDialogs.remove(fKey);
                        pendingContainers.remove(fKey);
                    }
                });
                pendingDialogs.put(fKey, dlg);
                dlg.show();
                diag("候选弹窗已显示（兜底）");
            } catch (Exception e) { error(e); toast("弹窗失败: " + e.getMessage()); diag("候选弹窗异常: " + e); }
        }
    });
}

void showSimpleDialog(String key, String title, String message) {
    final String fTitle = title;
    final String fMsg = message;
    final android.app.Activity act = waitActivity();
    if (act == null) {
        diag("弹窗被跳过（无 Activity）: " + title);
        toast(title + ": " + message);
        return;
    }
    act.runOnUiThread(new Runnable() {
        public void run() {
            try {
                AlertDialog.Builder b = new AlertDialog.Builder(act, getCurrentTheme());
                b.setTitle(fTitle);
                b.setMessage(fMsg);
                b.setPositiveButton("关闭", null);
                b.show();
            } catch (Exception e) { error(e); toast("弹窗失败: " + e.getMessage()); }
        }
    });
}

/** 停掉指定会话的加载动画 */
void stopLoadingAnim(String key) {
    android.os.Handler h = pendingAnimHandlers.get(key);
    if (h != null) {
        h.removeCallbacksAndMessages(null);
        pendingAnimHandlers.remove(key);
    }
}

/** 关闭指定会话的弹窗（发送后收起；不依赖 getActivity，直接投递到主线程） */
void dismissDialog(String key) {
    final String fKey = key;
    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
        public void run() {
            dismissDialogNow(fKey);
        }
    });
}

int riskColor(int risk) {
    if (risk >= 7) return Color.parseColor("#D32F2F");
    if (risk >= 4) return Color.parseColor("#EF6C00");
    return Color.parseColor("#2E7D32");
}

// ==================== 工具 ====================
int dp(int dip) { return (int) (dip * context.getResources().getDisplayMetrics().density); }

int getCurrentTheme() {
    try {
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
                ? AlertDialog.THEME_DEVICE_DEFAULT_DARK
                : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
    } catch (Exception e) {
        return AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
    }
}

/** 诊断日志：写入 appPath/jev_diag.log（追加） */
void diag(String line) {
    try {
        String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date());
        String old = readFileText(appPath + "/" + DIAG_FILE);
        if (old == null) old = "";
        if (old.length() > 20000) old = old.substring(old.length() - 15000);
        writeTextToFile(appPath + "/" + DIAG_FILE, old + "\n[" + time + "] " + line);
    } catch (Exception e) {
        try { log("diag写入失败: " + e); } catch (Exception e2) { }
    }
}
