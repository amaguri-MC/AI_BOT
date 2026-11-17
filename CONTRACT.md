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

---
# v0.2 追記（自力でエンドラまで・本物のサバイバル・速い動き・見ていない所は早送り）

## 全体の流れ
- 行動を決めるのは **Autopilot（run パッケージ）**。AI（脳）は喋るのと、たまに「ついてく」等の割り込みだけ。脳を待って止まることは無い
- 召喚時の持ち物は**空**。道具は木→石→鉄と自分で作る。掘る速さ・ドロップはプレイヤーと同じ規則（持っている道具次第）
- 誰も近くにいない（全次元で160ブロック（ボットの追跡距離）内にプレイヤー無し、スペクテイターは「見ている」扱い）状態が20秒続いたら早送り。人が近づいたら本当に動く

## W1 サバイバルの手（skill/ の Digger, MineSkill, Inv, Recipes, CraftSkill, SmeltSkill, SurvivalSkills, EatSkill）
```java
final class Inv { // FakeMember.getInventory() の操作をここに集約（他担当も使う）
  static int count(FakeMember o, Item i); static int count(FakeMember o, TagKey<Item> t);
  static boolean remove(FakeMember o, Item i, int n); static void add(FakeMember o, ItemStack s); // 入らない分は足元に落とす
  static ItemStack bestTool(FakeMember o, BlockState s);   // 一番速い道具（無ければ EMPTY）
  static ItemStack bestWeapon(FakeMember o);
  static void equip(FakeMember o, ItemStack s);            // 手に持たせる（見た目と攻撃力に反映。実物の道具を持つ）
  static void damageTool(FakeMember o, ItemStack s, int n); // 耐久を減らし0で壊す（音つき）
}
public final class SurvivalSkills {
  static Skill create(String name, FakeMember o, SkillRunner r, JsonObject in); // 知らない名前は null
  static void specs(JsonArray out);
}
```
- スキル名: `mine{block,count}`（既存を本物化）`craft{item,count}` `smelt{item,count}`（焼く・食べ物を焼くも）`eat`
- 破壊時間はバニラと同じ式（硬さ・道具の速さ・適正道具か）。適正道具でないとドロップ無し。道具の耐久を減らす
- craft: 2x2 は手元で、3x3 は近く4ブロック内の作業台（無ければ作って置く）。レシピは RecipeManager か手書き表（原木→板材, 棒, 作業台, 木/石/鉄のツルハシ・斧・剣・シャベル, かまど, バケツ, 火打石と打ち金, 盾, ブレイズパウダー, エンダーアイ, パン, 鉄装備, ベッド）。1回10tick＋腕振り
- smelt: かまどを置き、燃料（石炭/木炭/板材/原木）で1個200tick。かまどの前で待つ
- 失敗は result「できない: 材料不足 鉄インゴット×2」の形

## W2 速い動きと戦い（skill/ の Nav, Motion, FollowSkill, GotoSkill, ExploreSkill, AttackSkill, Combat, BridgeSkill, PillarSkill, BarterSkill, DragonSkill, MotionSkills ＋ FakeMember の属性定義ブロックだけ）
```java
final class Combat { static void tick(FakeMember o); }   // SkillRunner.tick が毎tick呼ぶ反射。必要な時だけ今のスキルを一時停止して戦う
public final class MotionSkills { static Skill create(...); static void specs(JsonArray out); }
```
- 移動: 3ブロック以上はダッシュ（setSprinting）、平地のダッシュ中は約11tickごとにジャンプ（ダッシュジャンプ）。直線の平地でダッシュ約5.6ブロック/秒、ダッシュジャンプ約7ブロック/秒になるよう MOVEMENT_SPEED と speedModifier を調整
- 迷わない: 経路の再計算は1秒に1回まで。5秒詰まったら 掘って進む→柱→橋 の順。溶岩は避ける。5ブロック以上落ちそうで水バケツがあれば水で着地
- 戦い: 一番強い武器に持ち替え、攻撃は0.6秒間隔（プレイヤーのクールダウン）、ジャンプ攻撃でクリティカル、盾があればスケルトン/クリーパーに構える、横に動いてよける、体力8以下で下がって食べる
- スキル名: `goto` `follow_player` `explore` `attack{target}` `hunt{target: cow|pig|sheep|chicken|enderman|blaze}` `bridge{direction,length}` `pillar{height}` `barter{gold}`（金インゴットをピグリンに投げて落ちた物を拾う）`fight_dragon`（クリスタルを壊す→止まり木の竜を殴る→息をよける。倒したら event「エンドラ倒した」）

## W3 自動進行と早送り（run/**, skill/SkillRunner.java, skill/Tools.java, skill/PortalSkill.java, skill/StructureSkill.java, skill/RunSkills.java, entity/FakeMember の tick 呼び出しと保存/読込の数行, command/AiBuddyCommands の run サブコマンド）
```java
public final class Autopilot {          // FakeMember が1つ持つ（FakeMember.autopilot()）
  public void tick();                   // 毎tick。暇なら2秒以内に次の行動を SkillRunner に出す
  public void pause(int ticks, String why); public void resume();
  public boolean isEnabled(); public void setEnabled(boolean on);
  public String stageName(); public String goalText();   // 「ブレイズロッド集め（4/7）」
  public boolean isFastForwarding();
  public net.minecraft.nbt.CompoundTag save(); public void load(net.minecraft.nbt.CompoundTag t);
}
```
- 段階: 木→石の道具→鉄（バケツ・盾・火打石）→食料→ネザーゲート（溶岩だまりに水バケツで黒曜石を作って枠を組む。廃ポータルがあれば使う）→ネザー要塞でブレイズロッド7→ピグリン交換かエンダーマンでパール12→アイ作成→アイを投げて要塞へ（EyeOfEnder を実際に飛ばす）→エンドポータルにアイをはめる→エンドラ→完了
- 各段階の終わりに postEvent（「石ツール揃った」「ゲート作った 座標 x y z」など）
- SkillRunner: SurvivalSkills / MotionSkills / RunSkills を順に create する形に変え、`lastResult()` を public に。toolSpecs は脳用の少数（say 以外: follow_player, stop_follow, resume_run）だけ返す
- 早送り: 段階ごとの標準時間（合計約12分）で進め、終わったら手に入るはずの物を持ち物に足し、次の場所（要塞・ネザー要塞など）へテレポート・次元移動。人が近づいたら即やめる
- コマンド: `/aibuddy run on|off|status|skipto <stage>`。召喚直後は on

## W4 脳（brain/**）
- 脳は「喋り役」。ゲームの進行は Autopilot 任せ。「次どうする？」と聞かない。指示待ちしない
- 声もチャットも聞こえている前提を共通指示に明記。入力のチャット行に (声)/(チャット) を付ける
- 呼ぶタイミング: 出来事・チャット・声のときだけ＋暇つぶしの独り言は45秒に1回まで。返事済みのチャットは次から送らない（直近6件まで）
- プロンプトキャッシュ: system と tools に cache_control（ephemeral）。usage の cache_read をログに残す
- ツール: say / wait / follow_player（Autopilot を60秒止めてついていく）/ resume_run
- prompt_common.txt が旧既定文のままなら新既定文で上書き（ユーザーが書き換えていたら残す）

## W5 自動テスト（src/gametest/**, build.gradle の gametest 設定, .github/workflows/gametest.yml）
- サーバー gametest: (1) 召喚直後の持ち物が空 (2) 素手で石を掘ると時間がかかりドロップ無し、木のツルハシなら丸石 (3) 平らな40ブロックの直線でダッシュ速度が 5〜7.5 ブロック/秒 (4) 原木4→作業台・木のツルハシを作れる
