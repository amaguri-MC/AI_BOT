# CONTRACT（担当間の約束。変えるときは司令塔に相談）

modid `aibuddy` / base package `com.amaguri.aibuddy` / MC 26.2 Fabric。
API の裏取りは `/home/claude/apidump/mc/common.txt` `client.txt`（javap出力）と `/home/claude/apidump/fabric/fabric.txt`。grep して実在を確認してから使う。

## A: entity（担当A）`com.amaguri.aibuddy.entity`
```java
public final class ModEntities { public static EntityType<FakeMember> FAKE_MEMBER; public static void init(); }
public class FakeMember extends PathfinderMob {
  // 同期データ（クライアントで読む）
  public float getMelt();            // 0=元の形, 1=完全に溶けて水たまり
  public void setMelt(float v);      // 0..1 にclamp
  public String getSkinName();       // スキンを借りる MCID
  public String getDisplayNameText();// 名前タグ・チャットに出す名前
  public boolean isPuddle();         // getMelt() >= 1
  // サーバー専用
  public String getPersonaId();
  public SimpleContainer getInventory();          // 36枠。拾った物はここ
  public void say(String text);                   // 全員のチャットに「<名前> text」をプレイヤー発言風に流す。溶け>0.5 なら Slur.apply で崩す
  public void postEvent(String text);             // 出来事を脳(C)に知らせる（キューに積む）
  public java.util.List<String> drainEvents();    // C が取り出す
  public com.amaguri.aibuddy.skill.SkillRunner skills(); // D の実装を保持
}
public final class Slur { public static String apply(String text, float melt, RandomSource r); }
```
- 溶け: 毎tick +1/12000（約10分で1.0）。`/aibuddy freeze` 中は進まない。半分(0.5)で `postEvent("体が半分溶けてきた")`、1.0で `postEvent("完全に溶けて水たまりになった")`
- たこ焼き: プレイヤーが ModItems.TAKOYAKI を持って右クリック → 1個消費、溶け −0.5、食べる音とパーティクル、`postEvent("<プレイヤー名>にたこ焼きをもらった")`
- 水たまり中: 移動・スキル停止（skills().stop()）、無敵ではない
- 能力: 移動速度・攻撃力・体力は上手いプレイヤー相当（体力20、攻撃は持っている武器に応じる）
- コマンド（OP2）: `/aibuddy spawn <personaId>` `/aibuddy remove <personaId|all>` `/aibuddy melt <0..1>` `/aibuddy freeze <true|false>` `/aibuddy reload`（reload は `com.amaguri.aibuddy.brain.BrainManager.reload()` を呼ぶ）
- spawn はペルソナを `BrainManager.persona(id)` から取り（無ければエラー表示）、skin と displayName を設定し、`BrainManager.attach(member)` を呼ぶ

## B: client（担当B）`com.amaguri.aibuddy.client`
- `AiBuddyClient implements ClientModInitializer`。FakeMember をプレイヤーモデル＋`getSkinName()` のスキンで描く（`Minecraft.getInstance().playerSkinRenderCache()` と `ResolvableProfile`）。手持ちアイテム・名前タグ・歩き/振り/腕振りを出す
- 溶け描画: getMelt() に応じて頂点を変形（下に垂れる・横に広がる・背が縮む・顔が崩れる）、足元にスライム色ではなく本人の肌/服の色を混ぜた水たまり、垂れる滴パーティクル。1.0で平たい水たまりだけ

## C: brain（担当C）`com.amaguri.aibuddy.brain`
```java
public final class BrainManager {
  public static void init();                       // 設定読込・tick登録・チャット購読
  public static void reload();
  public static Persona persona(String id);        // 無ければ null
  public static void attach(FakeMember m);
  public static void detach(FakeMember m);
}
public record Persona(String id, String displayName, String skin, String systemPrompt, java.util.List<String> fallbackLines) {}
```
- 設定は `config/aibuddy/config.json`（apiKey / model 既定 "claude-haiku-5-5" / thinkIntervalSec 既定8 / maxTokens）と `config/aibuddy/personas/*.json`。apiKey が空なら環境変数 ANTHROPIC_API_KEY。**キーや実在の人名をリポジトリに入れない**
- Claude Messages API をツール使用で呼ぶ。ツールは D の `SkillRunner.toolSpecs()` ＋ `say` ＋ `wait`

## D: skills（担当D）`com.amaguri.aibuddy.skill`
```java
public final class SkillRunner {
  public SkillRunner(FakeMember owner);
  public static com.google.gson.JsonArray toolSpecs();   // Claude API の tools 配列（name/description/input_schema）
  public String start(String tool, com.google.gson.JsonObject input); // すぐ返す結果文（「開始した」「できない: 理由」）
  public void tick();           // FakeMember.tick から毎tick呼ばれる
  public void stop();
  public boolean isBusy();
  public String status();       // 「ダイヤを掘っている(3/5)」など脳に渡す現在の状況
  public String observe();      // 周囲の要約（体力・手持ち・近くのプレイヤー/敵/鉱石・時間・次元・座標）
}
```
