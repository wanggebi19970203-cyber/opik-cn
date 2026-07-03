import { useMutation, useQueryClient } from "@tanstack/react-query";
import api from "../api";
import { AxiosError } from "axios";
import i18next from "i18next";
import { useToast } from "@/ui/use-toast";
import { UserPermission } from "../types";

export interface WorkspaceUserPermissions {
  userName: string;
  permissions: UserPermission[];
}

export interface UpdateWorkspaceUsersPermissionsVariables {
  workspaceId: string;
  usersPermissions: WorkspaceUserPermissions[];
}

const UPDATE_WORKSPACE_USERS_PERMISSIONS_ENDPOINT =
  "/permissions/workspace/users";

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ message?: string; msg?: string }>;
  return (
    axiosError?.response?.data?.message ||
    axiosError?.response?.data?.msg ||
    i18next.t("common:comet.failedToUpdatePermissions")
  );
};

async function updateWorkspaceUsersPermissionsRequest(
  variables: UpdateWorkspaceUsersPermissionsVariables,
) {
  const { data } = await api.post(UPDATE_WORKSPACE_USERS_PERMISSIONS_ENDPOINT, {
    workspaceId: variables.workspaceId,
    usersPermissions: variables.usersPermissions,
  });

  return data;
}

export function useUpdateWorkspaceUsersPermissionsMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "update-users-permissions"],
    mutationFn: updateWorkspaceUsersPermissionsRequest,
    onSuccess: (_, variables) => {
      toast({ description: i18next.t("common:comet.permissionsUpdatedSuccessfully") });
      queryClient.invalidateQueries({
        queryKey: [
          "workspace-permissions",
          { workspaceId: variables.workspaceId },
        ],
      });
    },
    onError: (error) => {
      const message = extractServerMessage(error);
      toast({ description: message, variant: "destructive" });
    },
  });
}
