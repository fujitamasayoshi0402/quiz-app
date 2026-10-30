import { useMutation } from "@tanstack/react-query";
import { Platform, Share, Text, View } from "react-native";
import { Button, ErrorMessage, styles } from "@/components/ui";
import { useEnvironment } from "@/lib/environment-provider";
import { createShareLink } from "@/lib/share";

/**
 * 結果を共有する（web の `ShareResult`、DEV-212）。押すと共有のリンクを作り、OS の共有のシートで渡す。
 * 貼った先は、web と同じページとカードの画像（OGP）を出す。
 *
 * web と違い、作ってからもう一度押してもらう必要はない。アプリの共有のシートは、通信を待ったあとでも開ける。
 * 作ったリンクは覚えておき、もう一度押したときは作り直さない。
 * つなぐ先に web の origin が無いビルドでは、ボタンを出さない
 */
export function ShareResult({ slug, attemptId }: { slug: string; attemptId: string }) {
  const { environment } = useEnvironment();
  const create = useMutation({ mutationFn: () => createShareLink(environment, slug, attemptId) });
  if (!environment.webOrigin) return null;

  const share = async () => {
    const url = create.data ?? (await create.mutateAsync());
    // Android は url を受け付けない。iOS は message にも入れると、同じリンクが 2 つ並ぶ
    await Share.share(Platform.OS === "ios" ? { url } : { message: url });
  };

  return (
    <View style={{ gap: 8 }}>
      {create.error && <ErrorMessage error={create.error} />}
      <Button
        variant="secondary"
        label={create.isPending ? "準備しています…" : "結果を共有する"}
        disabled={create.isPending}
        // 失敗は上に出す（create.error）。共有のシートを閉じただけのときは何もしない
        onPress={() => void share().catch(() => undefined)}
      />
      <Text style={styles.muted}>
        リンクには、カテゴリの名前と正答率だけが出ます。あなたの名前やテナントは出ません。
      </Text>
    </View>
  );
}
