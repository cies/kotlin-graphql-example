import { redirect } from "next/navigation";
import { isPublicRegistrationEnabled } from "@/lib/env/public-registration";
import { SignupClient } from "./signup-client";

export default async function SignupPage() {
  if (!isPublicRegistrationEnabled()) {
    redirect("/auth/login");
  }
  return <SignupClient />;
}
