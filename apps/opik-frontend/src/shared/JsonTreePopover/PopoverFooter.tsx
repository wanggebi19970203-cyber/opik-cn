import React from "react";
import { Trans } from "react-i18next";
import KeyboardBadge from "./KeyboardBadge";

const PopoverFooter: React.FC = () => {
  return (
    <div className="border-t px-4 py-3">
      <p className="comet-body-xs text-light-slate">
        <Trans
          i18nKey="common:jsonTree.pressOrToNavigate"
          components={{
            1: <KeyboardBadge>Tab</KeyboardBadge>,
            3: <KeyboardBadge>←↑→↓</KeyboardBadge>,
            5: <KeyboardBadge>Enter</KeyboardBadge>,
            7: <KeyboardBadge>Esc</KeyboardBadge>,
          }}
        />
      </p>
    </div>
  );
};

export default PopoverFooter;
