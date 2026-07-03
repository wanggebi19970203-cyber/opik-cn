import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Send, UserPlus } from "lucide-react";
import { useTranslation } from "react-i18next";
import i18next from "i18next";

import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { useOpikWorkspaceName } from "@/store/AppStore";
import useUser from "@/plugins/comet/useUser";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import { useInviteUsersMutation } from "@/plugins/comet/api/useInviteMembersMutation";

const USERNAME_MIN_LENGTH = 3;
const USERNAME_MAX_LENGTH = 64;
const usernameRegex = new RegExp(
  `^[a-zA-Z0-9_.+-]{${USERNAME_MIN_LENGTH},${USERNAME_MAX_LENGTH}}$`,
);

const isEmail = (val: string) => z.string().email().safeParse(val).success;
const isUsername = (val: string) => usernameRegex.test(val);
const isValidUser = (val: string) => isUsername(val) || isEmail(val);

export const inviteUsersSchema = z.object({
  users: z.string().superRefine((value, ctx) => {
    const t = i18next.getFixedT(null, "common");
    const items = value
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean);

    if (items.length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: t("validation.mustContainAtLeastOneUser"),
      });
      return;
    }

    const invalidItems = items.filter((item) => !isValidUser(item));

    if (invalidItems.length > 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: t("validation.invalidEntries", {
          entries: invalidItems.map((v) => `"${v}"`).join(", "),
        }),
      });
    }
  }),
});

type InviteUsersFormData = z.infer<typeof inviteUsersSchema>;

const InviteUsersForm = () => {
  const { t } = useTranslation("common");
  const workspaceName = useOpikWorkspaceName();
  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const form = useForm<InviteUsersFormData>({
    resolver: zodResolver(inviteUsersSchema),
    defaultValues: { users: "" },
  });

  const inviteUsersMutation = useInviteUsersMutation();

  const handleSubmit = (data: InviteUsersFormData) => {
    const users = data.users
      .split(",")
      .map((t) => t.trim())
      .filter(Boolean);

    const workspace = allWorkspaces?.find(
      (w) => w.workspaceName === workspaceName,
    );
    if (!workspace) {
      return;
    }

    inviteUsersMutation.mutate(
      {
        workspaceId: workspace.workspaceId,
        users,
      },
      {
        onSuccess: () => {
          form.reset();
        },
      },
    );
  };

  return (
    <div className="rounded-lg border bg-background p-4">
      <div className="mb-4 flex flex-col items-start gap-1.5">
        <div className="flex items-center gap-2">
          <UserPlus className="size-4 text-muted-slate" />
          <div className="comet-body-s-accented">{t("collaborators.inviteTeamMember")}</div>
        </div>
        <div className="comet-body-s text-muted-slate">
          {t("collaborators.inviteTeammatesDescription")}
        </div>
      </div>

      <Form {...form}>
        <form onSubmit={form.handleSubmit(handleSubmit)}>
          <div className="flex gap-3">
            <FormField
              control={form.control}
              name="users"
              render={({ field }) => (
                <FormItem className="flex-1">
                  <FormLabel className="sr-only">{t("labels.emailsOrUsernames")}</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      placeholder={t("placeholders.emailsOrUsernames")}
                      className="h-8"
                      tabIndex={-1}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <Button
              type="submit"
              disabled={
                form.formState.isSubmitting || inviteUsersMutation.isPending
              }
              className="shrink-0"
              size="sm"
            >
              <Send className="mr-1.5 size-3.5" />
              {t("buttons.sendInvite")}
            </Button>
          </div>
        </form>
      </Form>
    </div>
  );
};

export default InviteUsersForm;
