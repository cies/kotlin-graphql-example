"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SignaturePad } from "@/components/contracts/signature-pad";
import { signContractByToken } from "@/lib/actions/contracts";

interface SignContractFormProps {
  token: string;
  defaultEmail: string;
}

export function SignContractForm({ token, defaultEmail }: SignContractFormProps) {
  const router = useRouter();
  const [signerName, setSignerName] = useState("");
  const [signerEmail, setSignerEmail] = useState(defaultEmail);
  const [signatureSvg, setSignatureSvg] = useState<string | null>(null);
  const [agree, setAgree] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!signatureSvg) {
      setError("Signature is required.");
      return;
    }
    if (!agree) {
      setError("You must agree before signing.");
      return;
    }

    setLoading(true);
    setError(null);
    const result = await signContractByToken({
      token,
      signerName,
      signerEmail,
      signatureSvg,
      userAgent: window.navigator.userAgent,
      ipAddress: undefined,
    });
    setLoading(false);

    if (result.error) {
      setError(result.error);
      return;
    }
    router.push("/auth/login?contractSigned=1");
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="signerName">Your name</Label>
          <Input id="signerName" value={signerName} onChange={(e) => setSignerName(e.target.value)} required />
        </div>
        <div className="space-y-2">
          <Label htmlFor="signerEmail">Your email</Label>
          <Input id="signerEmail" type="email" value={signerEmail} onChange={(e) => setSignerEmail(e.target.value)} required />
        </div>
      </div>

      <div>
        <Label>Signature</Label>
        <SignaturePad onChange={setSignatureSvg} />
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
        I agree to sign this contract electronically.
      </label>

      <Button onClick={submit} disabled={loading}>
        {loading ? "Submitting..." : "Sign contract"}
      </Button>
    </div>
  );
}
