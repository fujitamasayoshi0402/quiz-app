import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { FlatList, Text, View } from "react-native";
import { Button, ErrorMessage, Loading, Screen, styles } from "@/components/ui";
import { useListMyTenants, useListPlayableCategories } from "@/lib/api/generated/endpoints";
import { useAuth } from "@/lib/auth/auth-provider";

/** テナントのトップ。出題できるカテゴリと難易度を並べる。選んで解く画面は DEV-175 で足す */
export default function TenantHome() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  const router = useRouter();
  const { signOut } = useAuth();
  const tenants = useListMyTenants();
  const categories = useListPlayableCategories(slug);
  const tenant = tenants.data?.find((t) => t.slug === slug);

  return (
    <Screen>
      <Stack.Screen options={{ title: tenant?.name ?? "" }} />
      {categories.isPending ? (
        <Loading />
      ) : categories.isError ? (
        <ErrorMessage error={categories.error} />
      ) : (
        <FlatList
          data={categories.data}
          keyExtractor={(category) => category.id}
          ListEmptyComponent={<Text style={styles.text}>出題できるクイズがまだありません。</Text>}
          renderItem={({ item }) => (
            <View style={styles.row}>
              <Text style={styles.text}>{item.name}</Text>
              <Text style={styles.muted}>
                {item.difficulties.map((d) => d.name).join(" / ")}（{item.quizCount} 問）
              </Text>
            </View>
          )}
        />
      )}
      {(tenants.data?.length ?? 0) > 1 && (
        <Button label="テナントを切り替える" variant="secondary" onPress={() => router.replace("/")} />
      )}
      <Button label="ログアウト" variant="secondary" onPress={() => void signOut()} />
    </Screen>
  );
}
