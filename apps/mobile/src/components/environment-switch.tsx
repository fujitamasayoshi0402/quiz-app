import { useRouter } from "expo-router";
import { Text, View } from "react-native";
import { Button, styles } from "@/components/ui";
import { useEnvironment } from "@/lib/environment-provider";

/**
 * つなぐ先（dev / prod）を切り替える（DEV-208）。このビルドでつなげる先が 1 つなら出さない。
 *
 * 切り替えたら、最初の画面から始め直す。テナントはつなぐ先ごとに別で、開いていた画面は向こうに無いことがある。
 * ログインの状態はつなぐ先ごとに持つので、前にログインしていれば、そのまま入れる
 */
export function EnvironmentSwitch() {
  const router = useRouter();
  const { environment, environments, select } = useEnvironment();
  if (environments.length < 2) return null;

  return (
    <View style={{ gap: 8 }}>
      <Text style={styles.muted}>つなぐ先: {environment.label}</Text>
      {environments
        .filter((other) => other.name !== environment.name)
        .map((other) => (
          <Button
            key={other.name}
            label={`${other.label}に切り替える`}
            variant="secondary"
            onPress={() => {
              router.replace("/");
              void select(other.name);
            }}
          />
        ))}
    </View>
  );
}
