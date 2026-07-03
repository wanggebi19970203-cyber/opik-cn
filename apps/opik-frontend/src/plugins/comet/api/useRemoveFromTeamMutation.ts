import { useMutation, useQueryClient } from "@tanstack/react-query";
import api from "../api";
import { AxiosError } from "axios";
import i18next from "i18next";
import { useToast } from "@/ui/use-toast";
import { WORKSPACE_USERS_ROLES_QUERY_KEY } from "./useWorkspaceUsersRoles";

export interface RemoveFromTeamVariables {
  teamId: string;
  userName: string;
}

const REMOVE_FROM_TEAM_ENDPOINT = "/workspaces/removeFromTeam";

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ message?: string; msg?: string }>;
  return (
    axiosError?.response?.data?.message ||
    axiosError?.response?.data?.msg ||
    i18next.t("common:comet.failedToRemoveUserFromTeam")
  );
};

async function removeFromTeamRequest(variables: RemoveFromTeamVariables) {
  const { data } = await api.post(REMOVE_FROM_TEAM_ENDPOINT, {
    teamId: variables.teamId,
    userName: variables.userName,
  });

  return data;
}

export function useRemoveFromTeamMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "remove-from-team"],
    mutationFn: removeFromTeamRequest,
    onSuccess: (_, variables) => {
      toast({ description: i18next.t("common:comet.userRemovedFromTeamSuccessfully") });
      queryClient.invalidateQueries({
        queryKey: ["workspace-members", { workspaceId: variables.teamId }],
      });
      queryClient.invalidateQueries({
        queryKey: [
          "workspace-email-invites",
          { workspaceId: variables.teamId },
        ],
      });
      queryClient.invalidateQueries({
        queryKey: ["workspace-permissions", { workspaceId: variables.teamId }],
      });
      queryClient.invalidateQueries({
        queryKey: [
          WORKSPACE_USERS_ROLES_QUERY_KEY,
          { workspaceId: variables.teamId },
        ],
      });
    },
    onError: (error) => {
      const message = extractServerMessage(error);
      toast({ description: message, variant: "destructive" });
    },
  });
}
