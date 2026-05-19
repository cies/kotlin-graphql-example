import { CustomerForm } from "@/components/customers/customer-form";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export const metadata = { title: "New Customer" };

export default async function NewCustomerPage({ params }: Props) {
  const { orgSlug } = await params;

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">New Customer</h1>
      </div>
      <CustomerForm orgSlug={orgSlug} />
    </div>
  );
}
