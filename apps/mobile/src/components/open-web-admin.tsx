import { Linking, Text, View } from "react-native";
import { Button, styles } from "@/components/ui";
import { useEnvironment } from "@/lib/environment-provider";
import { webAdminUrl } from "@/lib/web-admin";

/**
 * 管理者に、いまのつなぐ先の web の管理画面を開くボタンを出す（DEV-215）。
 *
 * 解説のリンクと違い、アプリの中のブラウザの画面ではなく、端末のブラウザ（Safari、Chrome）で開く。
 * クイズを直すのは長くかかり、web のログインの状態もブラウザに残るため。アプリには、アプリの切り替えで戻る
 */
export function OpenWebAdmin({ slug, role }: { slug: string; role: string | undefined }) {
  const { environment } = useEnvironment();
  const url = webAdminUrl(environment, slug, role);
  if (!url) return null;

  return (
    <View style={{ gap: 4 }}>
      <Button label="管理画面を開く（web）" variant="secondary" onPress={() => void Linking.openURL(url)} />
      <Text style={styles.muted}>
        ブラウザで開きます。web のログインはアプリとは別で、初めて開くときはログインが要ります。
      </Text>
    </View>
  );
}
