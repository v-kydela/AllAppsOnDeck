# All Apps On Deck

<img width="72" height="160" alt="Screenshot_20261006-150014" src="https://github.com/user-attachments/assets/e9fd3265-fced-47af-a0c2-3e04b7251f96" />

This is my ideal Android launcher. Here are the main reasons why:

## No App Drawer

When a launcher has an app drawer, there's no way to tell which apps are on the home screens and which aren't. You might check every folder on every page before realizing the app you're looking for isn't there and you need to go to the app drawer. It's much better to have one non-duplicated list of apps, like on an iPhone.

## One Page

Even with an iPhone launcher, you still won't know which page the app you're looking for is on. Even if you do know where the app is, you may have to swipe across multiple pages and tap to open a folder to get there. When there's just one page, the folders are the only extra level of organization so every app is at most one tap deep.

Also, even if I try to manually remove the extra pages in the default Android launcher (Launcher3), the launcher nonsensically creates a second page every time I install a new app, no matter how much room there is on the first page. Enforcing a single-page format solves that problem as well.

## Automatic Organization

Launchers like Nova Launcher sell themselves on the idea of customizability, but that customizability only pertains to things like settings and behaviors. Those launchers never give me a button to perform a one-time action, like automatically moving all the apps from the app drawer to the home screens. When I install a new launcher I have to get everything set up manually by moving every app one at a time.

All Apps On Deck contains an actions menu with buttons for one-time tasks. Its crowning achievement is the "Auto Organize" button, which intelligently sorts all your apps into folders for you so you don't have to do it manually. Enjoy!

## Simplified Manual Organization

With typical launchers, dragging/dropping apps has a certain `ItemTouchHelper` behavior where item positions move during the drag in order to preview what the positions will be after the drop. This makes for a slippery situation when you try to drag an app onto a folder, since the folder will keep sliding out of the drop zone before you can release the drag. In All Apps on Deck, nothing moves until after you drop the item just where you want it.

### Disclaimer

Most of the code in this repo was written by Google Gemini.
