import { LoginForm } from "./login-form";
import { isPublicRegistrationEnabled } from "@/lib/env/public-registration";

export default async function LoginPage() {
  return <LoginForm registrationEnabled={isPublicRegistrationEnabled()} />;
}
