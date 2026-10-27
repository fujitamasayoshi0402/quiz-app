import { Redirect, useRouter } from "expo-router";
import { FlatList, Pressable, Text, View } from "react-native";
import { EnvironmentSwitch } from "@/components/environment-switch";
import { Button, ErrorMessage, Loading, Screen, styles } from "@/components/ui";
import { useListMyTenants } from "@/lib/api/generated/endpoints";
import { useAuth } from "@/lib/auth/auth-provider";

export default function Home() {
  const { status } = useAuth();
  if (status === "loading") return <Loading />;
  return status === "signedIn" ? <TenantPicker /> : <SignIn />;
}

function SignIn() {
  const { ready, error, signIn } = useAuth();
  return (
    <Screen>
      <View style={{ flex: 1, justifyContent: "center", gap: 16 }}>
        <Text style={styles.title}>Quiz</Text>
        <Text style={styles.text}>カテゴリと難易度を選んで、4 択のクイズに答えます。</Text>
        <Button label="ログイン" onPress={() => void signIn()} disabled={!ready} />
        {error && <Text style={styles.error}>{error}</Text>}
        <Text style={styles.muted}>パスキーとパスワードは、web で使っているものがそのまま使えます。</Text>
      </View>
      <EnvironmentSwitch />
    </Screen>
  );
}

/** 所属の数で出し分ける（要件定義「テナント未指定のとき」。web の `/` と同じ） */
function TenantPicker() {
  const router = useRouter();
  const { signOut } = useAuth();
  const tenants = useListMyTenants();

  if (tenants.isPending) return <Loading />;
  if (tenants.isError) {
    return (
      <Screen>
        <ErrorMessage error={tenants.error} />
        <Button label="もう一度読み込む" onPress={() => void tenants.refetch()} />
        <EnvironmentSwitch />
      </Screen>
    );
  }
  if (tenants.data.length === 1) return <Redirect href={`/t/${tenants.data[0].slug}`} />;

  return (
    <Screen>
      {tenants.data.length === 0 ? (
        <Text style={styles.text}>
          まだどのテナントにも所属していません。招待を受けるか、web の公開テナントの一覧から参加してください。
        </Text>
      ) : (
        <FlatList
          data={tenants.data}
          keyExtractor={(tenant) => tenant.slug}
          ListHeaderComponent={<Text style={styles.muted}>テナントを選んでください</Text>}
          renderItem={({ item }) => (
            <Pressable accessibilityRole="link" style={styles.row} onPress={() => router.push(`/t/${item.slug}`)}>
              <Text style={styles.text}>{item.name}</Text>
              {item.role === "admin" && <Text style={styles.muted}>管理者</Text>}
            </Pressable>
          )}
        />
      )}
      <EnvironmentSwitch />
      <Button label="ログアウト" variant="secondary" onPress={() => void signOut()} />
    </Screen>
  );
}
