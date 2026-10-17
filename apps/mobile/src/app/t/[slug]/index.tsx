import { Stack, useLocalSearchParams, useRouter } from "expo-router";
import { FlatList, Pressable, Text, View } from "react-native";
import { ResumeCard } from "@/components/resume-card";
import { Button, ErrorMessage, Loading, Screen, styles } from "@/components/ui";
import { useListMyTenants, useListPlayableCategories } from "@/lib/api/generated/endpoints";
import { useAuth } from "@/lib/auth/auth-provider";

/**
 * テナントのトップ。中断中の挑戦があれば先に出し、出題できるカテゴリと難易度を並べる。
 * カテゴリを押すと、そのカテゴリを選んだ状態で出題の条件を選ぶ
 */
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
          ListHeaderComponent={
            <View style={{ gap: 12, paddingBottom: 8 }}>
              <ResumeCard slug={slug} />
              <Button
                label="履歴を見る"
                variant="secondary"
                onPress={() => router.push({ pathname: "/t/[slug]/history", params: { slug } })}
              />
            </View>
          }
          ListEmptyComponent={<Text style={styles.text}>出題できるクイズがまだありません。</Text>}
          renderItem={({ item }) => (
            <Pressable
              accessibilityRole="link"
              style={styles.row}
              onPress={() => router.push({ pathname: "/t/[slug]/play", params: { slug, category: item.id } })}
            >
              <Text style={styles.text}>{item.name}</Text>
              <Text style={styles.muted}>
                {item.difficulties.map((d) => d.name).join(" / ")}（{item.quizCount} 問）
              </Text>
            </Pressable>
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
