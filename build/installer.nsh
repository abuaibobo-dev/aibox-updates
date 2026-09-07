!macro customInit
  ; Stop tray/background copies from older releases before files are replaced.
  nsExec::ExecToLog 'taskkill.exe /F /T /IM "极光工作箱.exe"'
  Sleep 800
!macroend
