/*
 * Author: Alec McKendell
 * Date: 2026.6.10
 * Description: Exports annotation details based on selections with nesting levels included.
*/


def omittedMdKeys = ["Parent_ID", "Parent_Name"]

// --- FUNCTIONS ---
// Function: Helper to run on FX thread. An error there is thrown again here, so the caller stops (rather
// than carrying on as if the code had run)
def runOnFxThreadAndWait( Closure fxCode ) {
    def latch = new CountDownLatch( 1 )
    def result = [value: null, error: null]
    Platform.runLater {
        try {
            result.value = fxCode.call()
        } catch (Throwable t) {
            result.error = t
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    if (result.error != null) { throw result.error }
    return result.value
}

// Function: Assign or update tooltips
def setTT( objs, String tooltip ) {
    objs.each { obj ->
        if (obj instanceof Control) {
            obj.getTooltip() ? obj.getTooltip().setText( tooltip ) : obj.setTooltip( new Tooltip( tooltip ) )
        } else {
            def currentTT = obj.getProperties().get( "tooltip" )
            if (currentTT) {
                currentTT.setText( tooltip )
            } else {
                def newTT = new Tooltip( tooltip )
                Tooltip.install( obj, newTT )
                obj.getProperties().put( "tooltip", newTT )
            }
        }
    }
}

// Function: Widest a window may be — the width of the screen it's on (or, before it's shown, its
// owner's screen), less a small margin
def maxWindowWidth( Window window ) {
    def ref = window?.isShowing() ? window : (window instanceof Stage ? window.getOwner() : null)
    def screen = ref ? Screen.getScreensForRectangle( ref.getX(), ref.getY(), ref.getWidth(), ref.getHeight() ).find() : null
    return (screen ?: Screen.getPrimary()).getVisualBounds().getWidth() - 20
}

// Function: Move a window back inside its screen if it hangs off the left or right edge
def keepOnScreen( Window window ) {
    def screen = Screen.getScreensForRectangle( window.getX(), window.getY(), window.getWidth(), window.getHeight() ).find() ?: Screen.getPrimary()
    def bounds = screen.getVisualBounds()
    double x = Math.min( window.getX(), bounds.getMaxX() - window.getWidth() )
    window.setX( Math.max( x, bounds.getMinX() ) )
}

// Function: Create window showing script progress
def createProgressWindow( String title, int totalImages, boolean simplify = false, Stage ownerStage = null, List imageNames = [] ) {
    return runOnFxThreadAndWait {
        def stage = new Stage()
        stage.setMinWidth( 350 )
        stage.setResizable( false )
        stage.setTitle( title )
        
        def imageNum = new SimpleStringProperty( "0" )
        def imageName = new SimpleStringProperty( "Starting..." )
        def imageLabel = new Label()
        imageLabel.textProperty().bind(
            Bindings.concat( "Image ", imageNum, "/", totalImages, ": ", imageName )
        )      
        def imageRow = new HBox( 5, imageLabel )
        
        def bar = new ProgressBar( 0.0 )
        bar.setPrefWidth( 350 )
        bar.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( bar, Priority.ALWAYS )
        def barRow = new HBox( 10, bar )
        
        def stopBtn = new Button( "Stop" )
        stopBtn.setDisable( false )
        def spacer = new Region()
        HBox.setHgrow( spacer, Priority.ALWAYS )
        def timerLabel = new Label( "Elapsed: 00:00:00" )       
        def bottomRow = new HBox( 10, stopBtn, spacer, timerLabel )
        bottomRow.setAlignment( Pos.CENTER_LEFT )
        
        List rows = [imageRow, barRow, bottomRow]
        
        if (simplify) {
            rows.removeAll( imageRow )
        }
        
        def vbox = new VBox( 10 )
        vbox.getChildren().addAll( rows )
        vbox.setPadding( new Insets( 10 ) )
        vbox.setAlignment( Pos.CENTER_LEFT )
        
        def updateMinWidth = {
            double preferred = rows.collect { it.getWidth() }.max() + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
            if ( preferred > stage.getWidth() ) {
                stage.setWidth( Math.min( preferred, maxWindowWidth( stage ) ) )
            }
        }
        rows.each { node ->
            node.setMinWidth( Region.USE_PREF_SIZE )
            node.widthProperty().addListener { obs, old, nw -> updateMinWidth() }
        }
        
        def updateStageWidth = {
            Platform.runLater {
                double maxRowPref = rows.collect { it.prefWidth(-1) }.max()
                double needed = Math.max(maxRowPref + vbox.getPadding().getLeft() + vbox.getPadding().getRight(), 350)
                if (needed > stage.getWidth()) {
                    stage.setWidth( Math.min( needed, maxWindowWidth( stage ) ) )
                }
            }
        }
        [imageNum, imageName].each { prop ->
            prop.addListener { obs, old, nw -> updateStageWidth() }
        }
        
        def scene = new Scene( vbox )
        stage.setScene( scene )
        
        def gui = QuPathGUI.getInstance()
        // An explicit ownerStage means we're nested inside another window that is
        // already modal in its own right (e.g. the preferences dialog). Making this
        // window modal too would stack a second, overlapping modal-block scope on the
        // same owner, which is what causes the owner to get hidden instead of cleanly
        // re-enabled once the inner window closes. Only self-modal when top-level.
        boolean nested = ownerStage != null
        stage.initOwner( ownerStage ?: gui?.getStage() )
        if (!nested) {
            stage.initModality( Modality.WINDOW_MODAL )
        }
        stage.show()

        // Never wider than the screen: any text line too long to fit (e.g. a long image name) is
        // shortened in the middle instead, keeping its start and end
        double maxContentWidth = maxWindowWidth( stage ) - vbox.getPadding().getLeft() - vbox.getPadding().getRight()
        rows.each { row ->
            row.getChildren().findAll { it instanceof Label }.each { label ->
                label.setMaxWidth( maxContentWidth )
                label.setTextOverrun( OverrunStyle.CENTER_ELLIPSIS )
            }
        }
        // The window grows rightwards from where it opened, so pull it back if that runs it off screen
        stage.widthProperty().addListener { obs, oldVal, newVal -> keepOnScreen( stage ) }

        // Pre-size once to fit the longest image name (plus every other row), never
        // below the minimum width, so the window opens at its final size rather than
        // growing as image names appear during the run. The grow-never-shrink logic
        // stays in place as a floor so unusually long step text still can't be clipped.
        String widestImageName = (imageNames ?: []).collect { it?.toString() ?: "" }.max { it.length() } ?: ""
        String initialImageName = imageName.get()
        String initialImageNum  = imageNum.get()
        imageName.set( widestImageName )
        imageNum.set( totalImages.toString() )
        vbox.applyCss()
        vbox.layout()
        // The stage width includes the OS window frame, so the usable scene is
        // narrower than the stage by its side borders (a few px on Windows, ~0 on
        // macOS). Add that back or the content's right edge gets clipped.
        double frameWidth = scene.getWidth() > 0 ? Math.max( stage.getWidth() - scene.getWidth(), 0 ) : 0
        double contentWidth = rows.collect { it.prefWidth( -1 ) }.max() + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
        double baselineWidth = Math.max( contentWidth + frameWidth, stage.getMinWidth() )
        if (baselineWidth > stage.getMinWidth()) { stage.setMinWidth( Math.min( baselineWidth, maxWindowWidth( stage ) ) ) }
        if (baselineWidth > stage.getWidth())    { stage.setWidth( Math.min( baselineWidth, maxWindowWidth( stage ) ) ) }
        imageName.set( initialImageName )
        imageNum.set( initialImageNum )
        updateStageWidth()
        
        def startTime = System.nanoTime()
        
        def timerTimeline = new Timeline(
            new KeyFrame(
                javafx.util.Duration.seconds( 1 ),
                { evt ->
                    long elapsedNs = System.nanoTime() - startTime
                    long seconds = (long)( elapsedNs / 1_000_000_000L )
                    long hrs = seconds / 3600
                    long mins = (seconds % 3600) / 60
                    long secs = seconds % 60
                    timerLabel.setText(
                        String.format( "Elapsed: %02d:%02d:%02d", hrs, mins, secs )
                    )
                }
            )
        )
        timerTimeline.setCycleCount( Animation.INDEFINITE )
        timerTimeline.play()
        
        // Shared atomic state written by worker threads, read by poll timeline
        def doneCount = new AtomicInteger( 0 )
        def lastImageName = new AtomicReference<String>( "Starting..." )
        
        // Poll at 100 ms — faster than the render pulse, so no burst is missed
        def pollTimeline = new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis( 100 ),
                { evt ->
                    int done = doneCount.get()
                    imageNum.set( "${done}" )
                    imageName.set( lastImageName.get() )
                    bar.setProgress( totalImages > 0 ? done / (double)totalImages : 0.0 )
                }
            )
        )
        pollTimeline.setCycleCount( Animation.INDEFINITE )
        pollTimeline.play()
        
        // Stop both timelines when the stage is actually closed
        stage.setOnHidden {
            timerTimeline.stop()
            pollTimeline.stop()
        }
        
        // Signal to stop script
        def stopLaterFlag = new AtomicBoolean( false )
        
        def stopLaterLabel = new Label( "Stopping after image..." )
        stopLaterLabel.setPadding( new Insets( 4 ) )
        
        def showStopConfirmation = {
            if (stopLaterFlag.get()) { return }
            def alert = new Alert( Alert.AlertType.CONFIRMATION )
            alert.setTitle( "Stop confirmation" )
            alert.setHeaderText( "Stop processing?" )
            alert.initOwner( stage )
            def response = alert.showAndWait().orElse( null )
            if (response == ButtonType.OK) {
                stopLaterFlag.set( true )
                bottomRow.getChildren().set( 0, stopLaterLabel )
            }
        }

        stopBtn.setOnAction { showStopConfirmation() }
        stage.setOnCloseRequest { evt ->
            evt.consume()
            showStopConfirmation()
        }
        
        return [
            stage        : stage,
            stopLaterFlag: stopLaterFlag,
            doneCount    : doneCount,
            lastImageName: lastImageName
        ]
    }
}

// Function: Lock a node's current dimensions to prevent resizing
def lockDimensions( Node nodes ) {
    Platform.runLater {
        nodes.each { node ->
            double h = node.getHeight()
            node.setMinHeight( h )
            node.setPrefHeight( h )
            node.setMaxHeight( h )
            double w = node.getWidth()
            node.setMinWidth( w )
            node.setPrefWidth( w )
            node.setMaxWidth( w )
        }
    }
}

// Function: Make a checkbox that selects/deselects all checkboxes
def makeSelectAllCB( List groupCBs ) {
    def selectAllCB = new CheckBox()
    
    // Select / deselect all action. Only the boxes on show: a hidden one keeps its state
    selectAllCB.setOnAction {
        selectAllCB.setIndeterminate( false )
        groupCBs.findAll { it.isVisible() }.each { it.setSelected( selectAllCB.isSelected() ) }
    }
    
    // Update relevant labels/instructions when boxes are clicked
    groupCBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->
            def shownCBs = groupCBs.findAll { it.isVisible() }
            int numSelected = shownCBs.count { it.isSelected() }
            
            if (numSelected !in [0, shownCBs.size()]) {
                selectAllCB.setIndeterminate( true )
            
            } else {
                selectAllCB.setIndeterminate( false )
                selectAllCB.setSelected( numSelected > 0 )
            }
        }
    } 
    
    return selectAllCB
}


// Function: Make a box with checkboxes and a modifiable header
def makeCheckboxBox( String title, String keyWord = null, List objects, int prefWidth = 0, int prefHeight = 0, String emptyMessage = "None available" ) {
    
    def checkboxBox = new VBox( 5 )
    checkboxBox.setPadding( new Insets( 5 ) )
    if (prefWidth > 0) {
        checkboxBox.setPrefWidth( prefWidth )
        checkboxBox.setMinWidth( prefWidth )
        checkboxBox.setMaxWidth( prefWidth )
    }
    if (prefHeight > 0) {
        checkboxBox.setPrefHeight( prefHeight )
    }
    
    def checkboxContainer = new VBox( 5 )
    checkboxContainer.setPadding( new Insets( 5 ) )
    
    // Build checkboxes from supplied objects
    def checkboxes = objects.collect { obj ->

        def cb = new CheckBox( obj.name )
        cb.setUserData( obj.data ?: obj.name )
        cb.setMnemonicParsing( false ) // so no characters are dropped

        return cb
    }

    def noneAvailableLabel = new Label( emptyMessage )
    noneAvailableLabel.setStyle( "-fx-font-style: italic; -fx-text-fill: gray;" )
    noneAvailableLabel.setPadding( new Insets( 5 ) )

    checkboxContainer.getChildren().add( noneAvailableLabel )
    checkboxes.each { checkboxContainer.getChildren().add( it ) }

    // Shows "None available" whenever there are no checkboxes, or none are currently visible
    def updateNoneAvailable = {
        boolean anyVisible = checkboxes.any { it.isVisible() }
        noneAvailableLabel.setVisible( !anyVisible )
        noneAvailableLabel.setManaged( !anyVisible )
    }
    updateNoneAvailable()
    checkboxes.each { cb -> cb.visibleProperty().addListener { obs, oldVal, newVal -> updateNoneAvailable() } }
    
    def selectAllCB = makeSelectAllCB( checkboxes )
    
    def scrollPane = new ScrollPane( checkboxContainer )
    VBox.setVgrow( scrollPane, Priority.ALWAYS )
    pinCheckboxesWhileScrolling( scrollPane, checkboxes )
    
    def titleLabel = new Label()
    titleLabel.setPadding( new Insets( 5, 0, 0, 0 ) )

    // Set initial wording so the box's purpose is clear even when there's nothing to check
    titleLabel.setText( "${title.capitalize()} [0]" )
    selectAllCB.setText( "${title.capitalize()} [0]" )

    // Floor the header at its widest count, so the count changing (e.g. [9] -> [10]) never nudges it.
    // Only a floor (minWidth), never a fixed width: the first box can be measured before the app's font
    // is applied, and a fixed width would then clip the title for good
    boolean selectAllFloorSet = false
    selectAllCB.widthProperty().addListener { obs, oldW, newW ->
        if (!selectAllFloorSet && newW.doubleValue() > 0) {
            selectAllFloorSet = true
            Platform.runLater {
                String initialText = selectAllCB.getText()
                selectAllCB.setText( "${title.capitalize()} [${checkboxes.size()}]" )
                selectAllCB.applyCss()
                double w = selectAllCB.prefWidth( -1 )
                double h = selectAllCB.prefHeight( w )
                selectAllCB.setText( initialText )

                selectAllCB.setMinWidth( w )
                selectAllCB.setMinHeight( h )
            }
        }
    }

    // Add listeners to checkboxes (for changing instructions/labels). Hidden boxes don't count as selected
    String TTword = keyWord ?: title
    def refreshLabels = {
        def selectedNow = checkboxes.findAll { it.isSelected() && it.isVisible() }.collect { it.getText() }
        titleLabel.setText( "${title.capitalize()} [${selectedNow.size()}]" )
        
        selectAllCB.setText( "${title.capitalize()} [${selectedNow.size()}]" )
        setTT( selectAllCB, selectAllCB.isSelected() ? "Deselect all ${TTword.uncapitalize()}" : "Select all ${TTword.uncapitalize()}" )
        
        String tooltipText = "Select ${TTword.uncapitalize()}"
        
        if (selectedNow.size() > 0) {
            tooltipText = "${TTword.capitalize()} selected:"
            selectedNow.eachWithIndex { item, i ->
                tooltipText += "\n ${i+1}. ${item}"
            }
        }
        
        setTT( [titleLabel, scrollPane], tooltipText )
    }
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> refreshLabels() }
        cb.textProperty().addListener { obs, oldVal, newVal -> refreshLabels() }
        
        // Hidden boxes don't count as selected, so showing/hiding one re-fires its selection listeners
        cb.visibleProperty().addListener { obs, oldVal, newVal ->
            cb.setSelected( !cb.isSelected() )
            cb.setSelected( !cb.isSelected() )
        }
    }
    refreshLabels()
    
    // Allowing shift + click to select/deselect multiple
    CheckBox lastClicked = null   
    checkboxes.eachWithIndex { cb, idx ->
    
        cb.addEventHandler( MouseEvent.MOUSE_CLICKED ) { e ->
    
            if (e.isShiftDown() && lastClicked != null) {
                int lastIdx = checkboxes.indexOf( lastClicked )
                int start = Math.min( lastIdx, idx )
                int end   = Math.max( lastIdx, idx )
    
                // JavaFX doesn't tick a box on a shift-click, so the range copies the box clicked before it
                boolean value = lastClicked.isSelected()
    
                (start..end).each { i ->
                    def other = checkboxes[ i ] // never reassign cb: this handler would keep it, and anchor later clicks on the wrong box
                    if (!other.isDisabled() && other.isVisible()) {
                        other.setSelected( value )
                    }
                }
            }
    
            lastClicked = cb
        }
    }
    
    def clearBtn = new Button()
    addIcon( clearBtn, FontAwesome.Glyph.TRASH, 14 )
    clearBtn.setOnAction { checkboxes.each { it.setSelected( false ) } }
    setTT( clearBtn, "Clear selected items" )
    
    def topRow = new HBox( 5 )
    topRow.setPadding( new Insets( 8 ) )
    
    def topRowSpacer = new Region()
    HBox.setHgrow( topRowSpacer, Priority.ALWAYS )
    
    
    checkboxBox.getChildren().addAll( topRow, scrollPane )
    
    checkboxBox.setStyle( "-fx-border-color: #000000" )  
    topRow.setStyle( "-fx-border-color: #000000; -fx-border-width: 0 0 1 0" )    
    scrollPane.setStyle( "-fx-background-color: transparent" )
    
    // Width needed to show the widest item without clipping, never less than the requested width.
    // Measures every checkbox, hidden ones included, so filtering the list never changes the width
    def naturalWidth = {
        double widest = noneAvailableLabel.prefWidth( -1 )
        checkboxes.each { cb ->
            cb.applyCss()
            double w = cb.prefWidth( -1 )
            // A checkbox whose text changes (e.g. a count that follows the selected images) can name the
            // widest text it may ever show, as a closure taking its font, so the box doesn't resize with it
            def widestText = cb.getProperties().get( "widestText" )
            if (widestText != null) {
                w += textWidth( widestText( cb.getFont() ), cb.getFont() ) - textWidth( cb.getText(), cb.getFont() )
            }
            widest = Math.max( widest, w )
        }
        def vBar = scrollPane.lookupAll( ".scroll-bar" ).find { it instanceof ScrollBar && it.orientation == Orientation.VERTICAL }
        double barW = (vBar != null && vBar.prefWidth( -1 ) > 0) ? vBar.prefWidth( -1 ) : 15 // always leave room for the bar
        double listW = widest + insetsW( checkboxContainer ) + insetsW( scrollPane ) + barW
        double contentW = Math.max( listW, topRow.prefWidth( -1 ) ) + insetsW( checkboxBox )
        return Math.ceil( Math.max( prefWidth, contentW ) )
    }

    return [
        box         : checkboxBox,
        header      : topRow,
        selectAll   : selectAllCB,
        title       : titleLabel,
        spacer      : topRowSpacer,
        clear       : clearBtn,
        CBs         : checkboxes,
        naturalWidth: naturalWidth,
        minWidth    : prefWidth > 0 ? prefWidth : 150 // narrowest the box may shrink to when the screen is too small
    ]
}

// Function: Keep each checkbox's box pinned at the left edge while its text scrolls horizontally. The
// text is drawn by a clipped Label set as the checkbox's graphic: the checkbox keeps its own text (so
// anything reading getText() is unaffected), it just doesn't draw it. Scrolling slides the whole
// checkbox right by the scroll offset and its Label back left by the same amount, so the words move
// with the scroll while the box stays put, and the clip hides the words once they reach the box
def pinCheckboxesWhileScrolling( ScrollPane scrollPane, List checkboxes ) {
    List labels = checkboxes.collect { cb ->
        def label = new Label()
        label.textProperty().bind( cb.textProperty() )
        // A graphic doesn't get the gap a checkbox normally leaves before its text, so add it back
        label.layoutXProperty().bind( Bindings.createDoubleBinding( { cb.getLabelPadding().getLeft() }, cb.labelPaddingProperty() ) )
        def holder = new Pane( label )
        def clip = new javafx.scene.shape.Rectangle()
        clip.xProperty().bind( label.layoutXProperty() ) // scrolled words vanish at the usual gap, not right against the box
        clip.widthProperty().bind( holder.widthProperty().subtract( label.layoutXProperty() ) )
        clip.heightProperty().bind( holder.heightProperty() )
        holder.setClip( clip )
        cb.setGraphic( holder )
        cb.setContentDisplay( ContentDisplay.GRAPHIC_ONLY )
        return label
    }

    // The scroll pane scrolls by shifting a wrapper around the content to a pixel-snapped position.
    // Follow that exact position rather than recomputing it from the scroll value — any rounding
    // difference between the two makes the boxes jiggle as you scroll
    def content = scrollPane.getContent()
    def update = {
        double offset = -(content.getLayoutX() + (content.getParent()?.getLayoutX() ?: 0))
        checkboxes.eachWithIndex { cb, i ->
            cb.setTranslateX( offset )
            labels[ i ].setTranslateX( -offset )
        }
    }
    content.layoutXProperty().addListener { obs, oldVal, newVal -> update() }
    content.parentProperty().addListener { obs, oldParent, newParent ->
        newParent?.layoutXProperty()?.addListener { o, oldVal, newVal -> update() }
        update()
    }
}

// Function: Rendered width of a string in a given font
def textWidth( text, font ) {
    def t = new javafx.scene.text.Text( text as String )
    t.setFont( font )
    return t.getLayoutBounds().getWidth()
}

// Function: The widest-rendering number with as many digits as max (digit glyphs differ in width, so
// e.g. "1" is narrower than "0") — for reserving room for a count that can grow up to max
def widestNumber( max, font ) {
    String widestDigit = ('0'..'9').max { textWidth( it, font ) }
    return widestDigit * "${max}".length()
}

// Function: Horizontal padding + border of a region
def insetsW( Region region ) {
    def ins = region.getInsets()
    return ins.getLeft() + ins.getRight()
}

// Function: Size each checkbox box to its widest item, widening the dialog to fit — but never past the
// edge of the screen; if everything can't fit, the widest boxes give up width first (down to their
// minimum, or 100px on a screen too small even for that) and their lists scroll horizontally.
// Re-fits whenever an item's text changes. Takes a closure returning the current box maps so rebuilt
// boxes are picked up; returns a closure that requests a re-fit (call it after rebuilding boxes)
def fitBoxesToScreen( Dialog dialog, Closure currentBoxes ) {
    boolean pending = false
    Double decoration = null // window frame width; measured once, as the scene lags behind window resizes
    def refit
    def requestRefit = {
        if (pending) { return }
        pending = true
        Platform.runLater { pending = false; refit() } // coalesce bursts of text changes into one pass
    }

    refit = {
        def window = dialog.getDialogPane().getScene()?.getWindow()
        if (window == null || !window.isShowing()) { return }
        List boxes = currentBoxes().findAll { it != null }

        boxes.each { b ->
            // The header's count floor (see makeCheckboxBox) lands a pulse after showing and can widen it
            if (!b.selectAll.getProperties().containsKey( "fitBoxesListener" )) {
                b.selectAll.getProperties().put( "fitBoxesListener", true )
                b.selectAll.minWidthProperty().addListener { obs, oldVal, newVal -> requestRefit() }
            }
            // Boxes that show a count title in their header (rather than the select-all) widen with it
            if (b.header.getChildren().contains( b.title ) && !b.title.getProperties().containsKey( "fitBoxesListener" )) {
                b.title.getProperties().put( "fitBoxesListener", true )
                b.title.textProperty().addListener { obs, oldVal, newVal -> requestRefit() }
            }
            b.CBs.each { cb ->
                if (!cb.getProperties().containsKey( "fitBoxesListener" )) {
                    cb.getProperties().put( "fitBoxesListener", true )
                    cb.textProperty().addListener { obs, oldVal, newVal -> requestRefit() }
                }
            }
        }

        // No max width, so a box can still stretch into spare room (e.g. when it's the BorderPane centre)
        def setBoxWidth = { b, double w ->
            b.box.setMinWidth( w )
            b.box.setPrefWidth( w )
            b.box.setMaxWidth( Double.MAX_VALUE )
        }

        List naturals = boxes.collect { it.naturalWidth() as double }
        boxes.eachWithIndex { b, i -> setBoxWidth( b, naturals[ i ] ) }

        def pane = dialog.getDialogPane()
        pane.applyCss()
        if (decoration == null) { decoration = Math.max( 0, window.getWidth() - window.getScene().getWidth() ) }
        double needed = pane.prefWidth( -1 ) + decoration

        def screen = Screen.getScreensForRectangle( window.getX(), window.getY(), window.getWidth(), window.getHeight() )
                .find() ?: Screen.getPrimary()
        def bounds = screen.getVisualBounds()
        double available = bounds.getWidth() - 20

        if (needed > available) {
            // Find the cap level where capping every box at max(level, its minimum) absorbs the excess.
            // Repeated, since boxes stacked in a column only free up the width of the widest of them
            List floors = (0..<boxes.size()).collect { i -> Math.min( boxes[ i ].minWidth as double, naturals[ i ] as double ) }
            def widthsAt = { List fl, double level -> (0..<naturals.size()).collect { i -> Math.min( naturals[ i ] as double, Math.max( level, fl[ i ] as double ) ) } }
            double target = naturals.sum()
            for (int pass = 0; pass < 6 && needed > available + 0.5; pass++) {
                target -= (needed - available)
                if (floors.sum() > target) { floors = floors.collect { Math.min( it as double, 100d ) } } // tiny screen
                double lo = 0, hi = naturals.max()
                30.times {
                    double mid = (lo + hi) / 2
                    if (widthsAt( floors, mid ).sum() > target) { hi = mid } else { lo = mid }
                }
                List capped = widthsAt( floors, lo )
                boxes.eachWithIndex { b, i -> setBoxWidth( b, Math.floor( capped[ i ] as double ) ) }
                pane.applyCss()
                needed = pane.prefWidth( -1 ) + decoration
            }
            needed = Math.min( needed, available )
        }

        // Grow (or shrink) around the window's current centre, then keep it on screen
        double oldW = window.getWidth()
        if (Math.abs( needed - oldW ) < 1) { return }
        double x = window.getX() - (needed - oldW) / 2
        window.setWidth( needed )
        x = Math.min( x, bounds.getMaxX() - needed )
        window.setX( Math.max( x, bounds.getMinX() ) )
    }

    dialog.setOnShown { requestRefit() }
    return requestRefit
}

// Function: Create icon for UI
def addIcon( node, iconCode, int size ) {
    def icon = IconFactory.createNode( iconCode, size )
    node.setGraphic( icon )
}

// Function: Outline an input in red for as long as it is a reason the "OK" button is disabled
def flagWhenBlocking( node, blocking, String blockedStyle = null ) {
    def outlineRedStyle = """
        -fx-border-color: red;
        -fx-border-radius: 3;
    """
    def flaggedStyle = blockedStyle ?: outlineRedStyle // e.g. an underline in place of an outline
    def clearedStyle = node.getStyle()                 // however the input looks when it isn't blocking

    // A node with no border of its own gets an invisible copy of the flag's border, so the flag
    // coming and going doesn't change the node's size and nudge the layout around it
    if (!clearedStyle?.contains( "-fx-border-color" )) {
        clearedStyle = (clearedStyle ?: "") + flaggedStyle.replace( "red", "transparent" )
    }

    // Hold the binding on the node — a derived binding (e.g. someBinding.not()) is only weakly
    // referenced by its dependencies, so otherwise it can be collected part way through the dialog
    node.getProperties().put( "flagWhenBlocking", blocking )

    def applyStyle = { boolean isBlocking -> node.setStyle( isBlocking ? flaggedStyle : clearedStyle ) }
    blocking.addListener { obs, oldVal, newVal -> applyStyle( newVal ) }
    applyStyle( blocking.getValue() )
}

// Function: While the mouse is over a disabled control, show why it's disabled. JavaFX never shows a
// disabled control's own tooltip (it isn't even picked under the mouse), so this follows the mouse from
// the window instead. reasons() gives the text: a String, or a map of each reason to whether it applies
// right now. Nothing shows while no reason applies
def explainWhenDisabled( Node node, Closure reasons ) {
    def tip = new Tooltip()
    def delay = new PauseTransition( tip.getShowDelay() ) // waits as long as an ordinary tooltip
    double mouseX = 0, mouseY = 0
    delay.setOnFinished {
        def window = node.getScene()?.getWindow()
        if (window?.isShowing()) { tip.show( window, mouseX + 10, mouseY + 7 ) } // where JavaFX puts its own
    }
    def hideTip = { delay.stop(); tip.hide() }

    def onMove = { MouseEvent e ->
        // The window picks the nearest enabled ancestor under the mouse: walk up to it through visible
        // nodes, and check the node itself is under the mouse (not a part scrolled out of view)
        def picked = e.getPickResult().getIntersectedNode()
        def n = node
        while (n != null && n != picked && n.isVisible()) { n = n.getParent() }
        boolean over = node.isDisabled() && picked != null && n == picked &&
                       node.contains( node.sceneToLocal( e.getSceneX(), e.getSceneY() ) )

        def why = over ? reasons() : null
        List lines = why instanceof Map ? why.findAll { it.value }.collect { it.key as String } : (why ? [why as String] : [])
        if (!lines) { hideTip(); return }
        tip.setText( lines.size() > 1 ? lines.collect { "• " + it }.join( "\n" ) : lines[ 0 ] )
        mouseX = e.getScreenX()
        mouseY = e.getScreenY()
        if (!tip.isShowing()) { delay.playFromStart() }
    } as javafx.event.EventHandler
    def onExit = { MouseEvent e -> hideTip() } as javafx.event.EventHandler

    // Follow the node into whichever window shows it
    def attach = { oldScene, newScene ->
        hideTip()
        oldScene?.removeEventFilter( MouseEvent.MOUSE_MOVED, onMove )
        oldScene?.removeEventHandler( MouseEvent.MOUSE_EXITED, onExit )
        newScene?.addEventFilter( MouseEvent.MOUSE_MOVED, onMove )
        newScene?.addEventHandler( MouseEvent.MOUSE_EXITED, onExit )
    }
    node.sceneProperty().addListener { obs, oldScene, newScene -> attach( oldScene, newScene ) }
    attach( null, node.getScene() )
    node.disabledProperty().addListener { obs, oldVal, isDisabled -> if (!isDisabled) { hideTip() } }
}

// Function: Outline in red the tick box of each selected image with a problem, with the problems as its
// tooltip. problemsWith( name ) lists an image's problems (none if it has none). The ring is drawn over the
// box's own edge and just outside it, so it takes no room and nothing moves as it comes and goes. The
// current image's box keeps its black border
def outlineImageProblems( List imageCBs, String currentImageName, Closure problemsWith ) {
    String currentStyle = "-fx-border-color: black; -fx-border-width: 2; -fx-border-radius: 1;"
    imageCBs.each { cb ->
        List problems = cb.isSelected() ? (problemsWith( cb.getText() ) ?: []) : []
        boolean isCurrent = cb.getText() == currentImageName
        String ring = "-fx-background-color: red, -fx-inner-border, -fx-body-color;" +
                      "-fx-background-insets: ${isCurrent ? -2 : -1}, 1, 2; -fx-background-radius: 4, 2, 1;"
        cb.lookup( ".box" )?.setStyle( (isCurrent ? currentStyle : "") + (problems ? ring : "") )
        problems ? setTT( cb, problems.join( "\n" ) ) : cb.setTooltip( null )
    }
}

// Function: Scan the entire project to collect annotation field availability by class
def collectProjectAnnotationIndex( project, Stage ownerStage = null, Collection omittedMdKeys = [] ) {
    List projectImages = project.getImageList()
    def collectingUI = createProgressWindow( "Getting project specifics", projectImages.size(), true, ownerStage, projectImages.collect { it.getImageName() } )
    int total = projectImages.size()
    def stopLaterFlag = collectingUI.stopLaterFlag

    def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), total )
    def pool = Executors.newFixedThreadPool( nThreads )

    def futures = projectImages.collect { entry ->
        pool.submit({
            // Bail out before touching image data once a stop has been requested
            if (stopLaterFlag.get()) {
                collectingUI.lastImageName.set( entry.getImageName() )
                collectingUI.doneCount.incrementAndGet()
                return [ classCounts: [:], measurements: [:], metadata: [:] ]
            }

            def localClassCounts     = [:].withDefault { 0 }
            def localMeasurements    = [:].withDefault { new LinkedHashSet() }
            def localMetadata        = [:].withDefault { new LinkedHashSet() }

            def imageData = null
            try {
                imageData = entry.readImageData()
                if (imageData) {
                    def annos = imageData.getHierarchy()?.getAnnotationObjects() ?: []
                    annos.each { obj ->
                        // Filed under the name the class list shows, so unclassified annotations are included
                        def className = classLabelForObject( obj )
                        localClassCounts[ className ]++
                        obj.getMeasurementList()?.getMeasurementNames()?.each { localMeasurements[ className ] << it }
                        obj.getMetadata()?.keySet()?.each {
                            if (!(it in omittedMdKeys)) { localMetadata[ className ] << it }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { imageData?.close() } catch (Exception ignored2) {}
            }

            collectingUI.lastImageName.set( entry.getImageName() )
            collectingUI.doneCount.incrementAndGet()

            return [ classCounts: localClassCounts, measurements: localMeasurements, metadata: localMetadata ]
        } as Callable)
    }

    // Serial merge — fast, no contention
    def index = [
        classCounts        : [:].withDefault { 0 },
        measurementsByClass: [:].withDefault { new LinkedHashSet() },
        metadataByClass    : [:].withDefault { new LinkedHashSet() }
    ]
    futures.each { future ->
        def local = future.get()
        local.classCounts.each    { k, v -> index.classCounts[ k ] = ( index.classCounts[ k ] ?: 0 ) + v }
        local.measurements.each   { k, v -> index.measurementsByClass[ k ].addAll( v ) }
        local.metadata.each       { k, v -> index.metadataByClass[ k ].addAll( v ) }
    }

    pool.shutdown()

    Thread.sleep( 200 )
    Platform.runLater { collectingUI.stage.close() }

    index.measurementsByClass = index.measurementsByClass.collectEntries { k, v -> [k, new LinkedHashSet( v )] }
    index.metadataByClass     = index.metadataByClass.collectEntries     { k, v -> [k, new LinkedHashSet( v )] }
    index.classCounts         = index.classCounts.collectEntries         { k, v -> [k, v as int] }
    index.cancelled           = stopLaterFlag.get()
    return index
}



// Function: Natural (alphanumeric) comparison so embedded numbers sort numerically rather than lexically
def naturalCompare( String s1, String s2 ) {
    def ta = []
    (( s1 ?: "" ).toLowerCase() =~ /\d+|\D+/).each { ta << it }
    def tb = []
    (( s2 ?: "" ).toLowerCase() =~ /\d+|\D+/).each { tb << it }
    int n = Math.min( ta.size(), tb.size() )
    for (int i = 0; i < n; i++) {
        String x = ta[ i ]
        String y = tb[ i ]
        int cmp = (x ==~ /\d+/ && y ==~ /\d+/) ? ((x as BigInteger) <=> (y as BigInteger)) : (x <=> y)
        if (cmp != 0) { return cmp }
    }
    return ta.size() <=> tb.size()
}


// Function: The class label used to identify an object by class. Mirrors QuPath's project
// summary (DefaultProject.HierarchySummary.pathClassToString), which the class lists come from: the
// full name, so a subclass is "Tumor: Positive" rather than getName()'s "Positive", and "Unclassified"
// for an object with no class. A label that differs would be selectable but never match.
def classLabelForObject( obj ) {
    return obj.getPathClass()?.toString() ?: "Unclassified"
}


// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null

// Each pane of a split viewer holds its own live ImageData, which can have changes not yet saved to
// disk. So any image open in *any* viewer is read through the instance that viewer holds, so the export
// matches what's on screen
def viewersByEntryId = [:]   // entry ID -> viewers currently showing that image
runOnFxThreadAndWait {
    QuPathGUI.getInstance().getAllViewers().each { viewer ->
        def viewerData = viewer.getImageData()
        if (viewerData == null) { return }
        def viewerEntry = project.getEntry( viewerData )
        if (viewerEntry == null) { return }
        viewersByEntryId.get( viewerEntry.getID(), [] ) << viewer
    }
}

// Viewers showing an image right now — a pane the user re-points mid-run stops counting
def viewersForEntry = { entry ->
    if (entry == null) { return [] }
    ( viewersByEntryId[ entry.getID() ] ?: [] ).findAll { viewer ->
        def viewerData = viewer.getImageData()
        viewerData != null && project.getEntry( viewerData )?.getID() == entry.getID()
    }
}

// The live ImageData a viewer holds for this image, or null when no pane is showing it
def liveDataForEntry = { entry ->
    def viewers = viewersForEntry( entry )
    viewers ? viewers[ 0 ].getImageData() : null
}

// Annotation class counts taken straight from a live hierarchy
def liveClassCounts = { imgData ->
    def counts = [:]
    imgData.getHierarchy().getAnnotationObjects().each { obj ->
        def cn = classLabelForObject( obj )
        counts[ cn ] = (counts[ cn ] ?: 0) + 1
    }
    return counts
}
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "AnnoTools-Data"
def scriptPrefsFileName   = "Save-Anno-Data_Preferences" + ".json"
def fieldCacheFileName    = "ProjectFieldCache" + ".json"

def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
if (!prefsFolder.exists()) { prefsFolder.mkdirs() }

def prefsFile = new File( prefsFolder, scriptPrefsFileName )
if (!prefsFile.exists()) { prefsFile.createNewFile() }

def fieldCacheFile = new File( prefsFolder, fieldCacheFileName )

// Read previous preferences
def prePrefsMap = [:]
def inputGson = new Gson()
def outputGson = new GsonBuilder().setPrettyPrinting().create()
if (prefsFile.length() > 0L && prefsFile.getText() != "null") {
    def readDataFile = inputGson.fromJson( prefsFile.getText(), Object )
    if (readDataFile instanceof Map) {
        prePrefsMap = readDataFile
    }
}

// Build map of project specifics to cache. scanVersion goes up whenever the scan starts collecting something
// new, so a cache from an older scan is rebuilt rather than reused (3: unclassified annotations are included,
// and subclasses are filed under their full name)
def projectFingerprint = { proj ->
    def imageNames = proj.getImageList().collect { it.getImageName() }.sort()
    def baseDir = proj.getBaseDirectory()?.getAbsolutePath() ?: ""
    return [ baseDir:     baseDir,
             images:      imageNames,
             scanVersion: "3" ]
}

// If a cache with this project's fingerprint exists, gets measurement/metadata specifics from there
// If not, regenerates by scanning the project
def loadProjectCache = { proj, File cacheFile, boolean forceReload = false, Stage ownerStage = null ->
    def currentFingerprint = projectFingerprint( proj )
    def previousCache = null
    if (cacheFile.exists() && cacheFile.length() > 0L) {
        try {
            def cached = inputGson.fromJson( cacheFile.text, Object )
            if (cached instanceof Map) { previousCache = cached }
            if (!forceReload && cached?.fingerprint == currentFingerprint && cached?.index instanceof Map) {
                return cached
            }
        } catch (Exception ignored) {
            // rebuild below
        }
    }

    def builtIndex = collectProjectAnnotationIndex( proj, ownerStage, omittedMdKeys )

    if (builtIndex.cancelled) {
        // Stopped partway through — keep whatever was already on disk rather than
        // persisting a partial scan as if it were complete, current data
        return previousCache ?
            previousCache + [ cancelled: true ] :
            [
                timeStamp  : "No cached data available",
                fingerprint: currentFingerprint,
                index      : builtIndex,
                cancelled  : true
            ]
    }

    def timestamp = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" )
    )

    def cacheObj = [
        timeStamp  : timestamp,
        fingerprint: currentFingerprint,
        index      : builtIndex
    ]

    cacheFile.text = outputGson.toJson(cacheObj)
    return cacheObj
}

def projectCache = loadProjectCache( project, fieldCacheFile )


// --- PREFERENCES POPUP ---
def codePreferences = runOnFxThreadAndWait {
    
    def dialog = new Dialog()
    dialog.setTitle( "What should be saved?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.setAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( " Save" )
    addIcon( okBtn, FontAwesome.Glyph.FLOPPY_ALT, 14 )
    
    
    
    // CENTER PANEL: PathClass selection list
    def gson = new Gson()
    def imageClassCounts = [:]  // imageName -> [className -> count]
    def totalsByClass = [:]     // className -> total across all images
    
    def imageList = project.getImageList()
    def nThreads = Math.min( Runtime.getRuntime().availableProcessors(), imageList.size() )
    def pool = Executors.newFixedThreadPool( nThreads )
    
    def futures = imageList.collect { entry ->
        pool.submit({
            def summaryFile = entry.getEntryPath().resolve( "summary.json" )
            def counts = [:]
            if (Files.exists( summaryFile )) {
                try {
                    def raw = new String( Files.readAllBytes( summaryFile ) )
                    def summary = gson.fromJson( raw, Object )
                    def classCounts = summary?.get( "hierarchy" )?.get( "annotationClassificationCounts" )
                    classCounts?.each { k, v ->
                        counts[ k.toString() ] = (v instanceof Number) ? ((Number)v).intValue() : 0
                    }
                } catch (Exception e) {
                    logger.warn( "[AnnoTools] summary parse failed for ${entry.getImageName()}: ${e.getMessage()}" )
                }
            }
            return [entry.getImageName(), counts]
        } as Callable)
    }
    
    // Serial merge directly into imageClassCounts and totalsByClass, no intermediate map needed
    futures.each { f ->
        def (imgName, counts) = f.get()
        imageClassCounts[ imgName ] = counts
        counts.each { className, count ->
            totalsByClass.merge( className, count, Integer.&sum )
        }
    }
    pool.shutdown()
    
    // If an image is open in a viewer, use its LIVE hierarchy for that image's class counts so
    // unsaved objects are reflected — both in the counts and in whether a class appears at all.
    // Every pane of a split viewer counts, not just the selected one, since the summary.json read
    // above only knows what was last saved. Cheap, since those images are already loaded, and safe
    // on the FX thread we're already running on.
    boolean anyLiveCounts = false
    project.getImageList().each { entry ->
        def liveData = liveDataForEntry( entry )
        if (liveData == null) { return }
        imageClassCounts[ entry.getImageName() ] = liveClassCounts( liveData )
        anyLiveCounts = true
    }

    // Rebuild totals in place so the overrides are reflected everywhere downstream
    if (anyLiveCounts) {
        totalsByClass.clear()
        imageClassCounts.each { imgName, counts ->
            counts.each { className, count ->
                totalsByClass.merge( className, count, Integer.&sum )
            }
        }
    }

    // Only classes with existing annotations in project, sorted by abundance
    List pathClassCBsMap = totalsByClass
        .collect { [name: it.key, data: it.key, num: it.value] }
        .sort { a, b -> (b.num <=> a.num) ?: naturalCompare( a.name, b.name ) }
        .collect { [name: it.name, data: it.data] } // labels set by updateClassCounts
    def pathClassBox = makeCheckboxBox( "Available classes", "classes of existing objects", pathClassCBsMap, 200 )
    pathClassBox.header.getChildren().addAll( pathClassBox.selectAll )
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses ) }
    BorderPane.setMargin( pathClassBox.box, new Insets( 0, 15, 0, 0 ) )
    
    root.center = pathClassBox.box
    
    
    
    // LEFT PANEL: Image Selection
    def imageNames = project.getImageList().collect { it.getImageName() }
    def imagesBox = makeCheckboxBox( "Select images", "images", imageNames.collect { [name: it] } )
    def currentImageCB = new CheckBox( "Current Image" )
    imagesBox.header.getChildren().addAll( imagesBox.selectAll, imagesBox.spacer, currentImageCB )
    imagesBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.selectedImages  ) }
    BorderPane.setMargin( imagesBox.box, new Insets( 0, 15, 0, 0 ) )
    
    if (currentEntry) {
        def currentCB = imagesBox.CBs.find { it.getText() == currentEntry.getImageName() }
        currentImageCB.selectedProperty().bindBidirectional(
            currentCB.selectedProperty()
        )
        
        // Setting bright border on the "Current Image" checkbox (the list's own is set by outlineImageProblems)
        Platform.runLater { currentImageCB.lookup( ".box" )?.setStyle( "-fx-border-color: black; -fx-border-width: 2; -fx-border-radius: 1;" ) }
        
        // If current image was only one selected, will update to new image
        if (prePrefsMap.currentImage == true && prePrefsMap.selectedImages.size() == 1) {
            imagesBox.CBs.each { it.setSelected( false ) }
            currentImageCB.setSelected( true )
        }
    } else {
        currentImageCB.setDisable( true )
        explainWhenDisabled( currentImageCB ) { "No image is open in the viewer" }
    }
    
    // Setting and updating tooltip for current image checkbox
    currentImageCB.selectedProperty().addListener { obs, oldVal, newVal ->
        setTT( currentImageCB, currentImageCB.isSelected() ? "Deselect current image" : "Select current image" )
    }
    
    root.left = imagesBox.box
    
    
    
    // Closure to update total and selected class counts
    def updateClassCounts = {
        def selectedNames = imagesBox.CBs.findAll { it.isSelected() }.collect { it.getText() } as Set
        def selectedTotals = [:]
        selectedNames.each { imgName ->
            imageClassCounts[ imgName ]?.each { className, count ->
                selectedTotals[ className ] = (selectedTotals[ className ] ?: 0) + count
            }
        }
        pathClassBox.CBs.each { cb ->
            def className = cb.getUserData()
            def sel   = selectedTotals[ className ] ?: 0
            def total = totalsByClass[ className ]  ?: 0
            cb.setText( total > 0 ? "${className} [${sel} / ${total}]" : className )
            // Reserve room for the widest the count can read, so selecting images doesn't resize the box
            cb.getProperties().put( "widestText", { font -> total > 0 ? "${className} [${widestNumber( total, font )} / ${total}]" : className } )
        }
    }
    
    imagesBox.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateClassCounts() }
    }
    updateClassCounts()

    // Red ring on each selected image without objects of the selected classes (only once a class is
    // chosen; until then the class box itself is flagged)
    def updateImageFlags = {
        def chosen = pathClassBox.CBs.findAll { it.isSelected() }*.getUserData()
        outlineImageProblems( imagesBox.CBs, currentEntry?.getImageName() ) { String name ->
            chosen && !chosen.any { imageClassCounts[ name ]?.get( it ) } ? ["No objects of the selected classes to save"] : []
        }
    }
    (imagesBox.CBs + pathClassBox.CBs).each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal -> updateImageFlags() }
    }
    Platform.runLater { updateImageFlags() } // tick boxes are only built once the dialog shows
    
    
    
    // RIGHT PANEL: Measurements and metadata
    def measurementsBox
    def metadataBox
    def bottomRightPanel = new HBox( 10 )
    
    def selectSpecificCBs = { CBs, List matches ->
        CBs.each { it.setSelected( it.getUserData() in matches ) }
    }
    
    // Closure to show only the measurements/metadata that objects of the selected classes have
    def showFieldsOfSelectedClasses = {
        def selectedPCs = pathClassBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        [[measurementsBox, projectCache.index.measurementsByClass], [metadataBox, projectCache.index.metadataByClass]].each { box, byClass ->
            Set available = selectedPCs.collectMany { byClass?.get( it ) ?: [] } as Set
            box.CBs.each { cbCheck ->
                boolean match = cbCheck.getText() in available
                cbCheck.setVisible( match )
                cbCheck.setManaged( match )
            }
        }
    }
    pathClassBox.CBs.each { cb -> cb.selectedProperty().addListener { obs, oldVal, newVal -> showFieldsOfSelectedClasses() } }
    
    // Closure to generate available measurements and metadata options by selected object class
    def setRightBox = {

        def measurementsByClass = projectCache.index.measurementsByClass ?: [:]
        LinkedHashSet allMeasurements = new LinkedHashSet( (measurementsByClass.values() ?: []).collectMany { it ?: [] } )
        def allMeasurementsMap = allMeasurements.collect { [name: it] }
        measurementsBox = makeCheckboxBox( "Available measurements", "existing measurements from objects of the selected classes", allMeasurementsMap, 250, 0, "None for selected classes" )
        measurementsBox.header.getChildren().add( measurementsBox.selectAll )
        
        def metadataByClass = projectCache.index.metadataByClass ?: [:]
        LinkedHashSet allMetadata = new LinkedHashSet( (metadataByClass.values() ?: []).collectMany { it ?: [] } )
        def allMetadataMap = allMetadata.collect { [name: it] }
        metadataBox = makeCheckboxBox( "Available metadata", "existing metadata from objects of the selected classes", allMetadataMap, 250, 0, "None for selected classes" )
        metadataBox.header.getChildren().add( metadataBox.selectAll )
        showFieldsOfSelectedClasses()
        
        bottomRightPanel.getChildren().setAll( measurementsBox.box, metadataBox.box )
        
        return [ mmCBs: measurementsBox.CBs,
                 mdCBs: metadataBox.CBs ]
    }
    
    def liveRightCBs = setRightBox()
    selectSpecificCBs( liveRightCBs.mmCBs, prePrefsMap.measurements )
    selectSpecificCBs( liveRightCBs.mdCBs, prePrefsMap.metadata )
    
    
    def timeStamp = new Label( " Last refresh: ${projectCache.timeStamp}" )
    addIcon( timeStamp, FontAwesome.Glyph.CLOCK_ALT, 14 )
    timeStamp.setPadding( new Insets( 5 ) )
    def refreshBtn = new Button( " Refresh project data" )
    addIcon( refreshBtn, FontAwesome.Glyph.REFRESH, 12 )
    setTT( refreshBtn, "Re-scan project to update existing measurements and metadata." )
    def topRow = new HBox( 10, refreshBtn, timeStamp )
    
    def rightPanel = new VBox( 10, topRow, bottomRightPanel )
    VBox.setVgrow( bottomRightPanel, Priority.ALWAYS ) // keep the boxes full height rather than shrinking to their contents
    
    root.right = rightPanel
    
    
    
    // BOTTOM ROW: Open file saver browser
    boolean save = false
    def filePath
    def folderPath
    okBtn.addEventFilter( ActionEvent.ACTION ) { e ->
        def chooser = new FileChooser()
        chooser.setTitle( "Choose save location" )
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter( "CSV files", "*.csv" )
        )
        
        // The last folder used, if it still exists (the chooser refuses one that doesn't)
        def lastFolder = prePrefsMap.folder ? new File( prePrefsMap.folder ) : null
        def startingDir = lastFolder?.isDirectory() ? lastFolder : project.getBaseDirectory()
    
        chooser.setInitialFileName( "results.csv" )
        chooser.setInitialDirectory( startingDir )
        
        def owner  = dialog.getDialogPane().getScene()?.getWindow()
        def chosen = chooser.showSaveDialog( owner )
        
    
        if (chosen == null) {
            e.consume()
            return
        }
        
        filePath = chosen.getAbsolutePath()
        if (!filePath.toLowerCase().endsWith( ".csv" )) {
            filePath += ".csv"
        }
        folderPath = chosen.getParent()
        
        save = true
    }
    
    
    
    // Disabling the "OK" button if not all inputs are met (only the image and class boxes count, and a
    // refresh never rebuilds those)
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedClass.not() )
    )

    // Red outline on each input while it is a reason the "OK" button is disabled
    flagWhenBlocking( imagesBox.box, anySelectedImage.not() )
    flagWhenBlocking( pathClassBox.box, anySelectedClass.not() )

    // Say why on hover, while disabled
    explainWhenDisabled( okBtn ) { [
        "Select at least one image": !anySelectedImage.get(),
        "Select at least one class": !anySelectedClass.get()
    ] }
    explainWhenDisabled( refreshBtn ) { timeStamp.getText().contains( "[CURRENT DATA]" ) ? "The project data shown is already current" : null }

    
    // Widen boxes to their widest item (window grows to fit, up to the screen width)
    def refitBoxes = fitBoxesToScreen( dialog ) { [imagesBox, pathClassBox, measurementsBox, metadataBox] }

    // Refresh project details
    refreshBtn.setOnAction {
        refreshBtn.setDisable( true )
        def dialogStage = dialog.getDialogPane().getScene().getWindow() as Stage

        // The progress window is non-modal while nested under this dialog (see
        // createProgressWindow), so disable the dialog's own content/buttons here
        // instead — it stands in for the modality we can no longer use
        dialog.getDialogPane().setDisable( true )

        List measurementsSelected = liveRightCBs.mmCBs.findResults { it.isSelected() ? it.getText() : null }
        List metadataSelected     = liveRightCBs.mdCBs.findResults { it.isSelected() ? it.getText() : null }

        Thread.startDaemon {
            def newCache = loadProjectCache( project, fieldCacheFile, true, dialogStage )

            Platform.runLater {
                projectCache = newCache
                liveRightCBs = setRightBox()
                selectSpecificCBs( liveRightCBs.mmCBs, measurementsSelected )
                selectSpecificCBs( liveRightCBs.mdCBs, metadataSelected )

                if (projectCache.cancelled) {
                    // Refresh didn't finish — leave the timestamp/button as if it
                    // never ran, rather than claiming the (possibly stale) data is current
                    timeStamp.setText( " Last refresh: ${projectCache.timeStamp}" )
                    refreshBtn.setDisable( false )
                } else {
                    timeStamp.setText( " Last refresh: ${projectCache.timeStamp}  [CURRENT DATA]" )
                    refreshBtn.setDisable( true )
                }

                dialog.getDialogPane().setDisable( false )
                refitBoxes() // the measurement/metadata boxes were just rebuilt

                // The progress window's owner here is the dialog, not the root QuPath
                // stage — so the root window never gets implicitly refocused when the
                // progress window/Alert close. Bring both back explicitly, main window
                // first so the dialog ends up on top of it, not the other way round.
                QuPathGUI.getInstance().getStage()?.toFront()
                dialogStage.toFront()
            }
        }
    }
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    dialog.showAndWait()

    // Same owner-chain gap as the refresh progress window — closing this dialog can
    // leave the root QuPath stage behind other applications, especially if a refresh
    // was cancelled earlier in the session
    QuPathGUI.getInstance().getStage()?.toFront()
    
    if (save) {
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        def selectedMeasurements = measurementsBox.CBs
                .findAll { it.isSelected() && it.isVisible() }
                .collect { it.getText() }
        def selectedMetadata = metadataBox.CBs
                .findAll { it.isSelected() && it.isVisible() }
                .collect { it.getText() }
        
        // Return all preferences if "OK" is selected
        return [
            selectedImages : selectedImages,
            currentImage   : currentImageCB.isSelected(),
            objectClasses  : selectedPathClasses,
            measurements   : selectedMeasurements,
            metadata       : selectedMetadata,
            file           : filePath,
            folder         : folderPath
        ]  
    }
}


// Exit if cancelling
if (codePreferences == null) {
    return
    
} else { // Remember the choices for next time
    
    prefsFile.text = outputGson.toJson( codePreferences )
}



def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total, false, null, imagesToRun.collect { it.getImageName() } )
def stopLaterFlag = progressUI.stopLaterFlag

// Making a matrix for all images
List completeMatrixMap = []
List headerRow = []
List emptyImages = []
boolean export = true

def nThreads = Math.min( Runtime.getRuntime().availableProcessors() * 2, imagesToRun.size() )
def pool = Executors.newFixedThreadPool( nThreads )
def rowResults = new Object[ imagesToRun.size() ]
def emptyImagesSync = Collections.synchronizedList( [] )
def outputMatrix = null   // built below but used after the window closes, so declared out here

// Image a failing task was working on, so the handler below can name it — "stopped after an
// error" is useless when the run covered a hundred images. First failure wins; the rest of the
// pool is torn down anyway.
def imageInFlight = new AtomicReference<String>( null )
def onImage = { String imageName, Closure work ->
    return {
        try {
            return work()
        } catch (Throwable t) {
            imageInFlight.compareAndSet( null, imageName )
            throw t
        }
    }
}

try {
def futures = imagesToRun.indices.collect { idx ->
    def image = imagesToRun[ idx ]
    def liveData = liveDataForEntry( image ) // looked up here, on the script thread, not in the pool
    pool.submit( onImage( image.getImageName(), {
        if (stopLaterFlag.get()) { return } // stopped: leave the images not yet started
        def imageData = liveData ?: image.readImageData()
        try {
            def hierarchy = imageData.getHierarchy()
            def annotations = hierarchy.getAnnotationObjects().findAll {
                classLabelForObject( it ) in codePreferences.objectClasses
            }
            def pixelCalibration = imageData.getServer().getMetadata().getPixelCalibration()
            String imageName = image.getImageName()
            
            if (annotations.isEmpty()) {
                emptyImagesSync << imageName
                rowResults[ idx ] = []
                progressUI.lastImageName.set( imageName )
                progressUI.doneCount.incrementAndGet()
                return
            }
            
            def localRows = []
            annotations.each { anno ->
                def parent = anno.getParent()
                Map rowMap = [:]
                
                // Whole ancestry in one cell, outermost ancestor first and this annotation last, so
                // any nesting depth still fits a single column
                def idPath = []
                def ancestor = anno
                while (ancestor != null && !ancestor.isRootObject()) {
                    idPath << ancestor.getID()
                    ancestor = ancestor.getParent()
                }
                
                rowMap[ "Image" ]                = imageName
                rowMap[ "Anno_Name" ]            = anno.getName()
                rowMap[ "Anno_Class" ]           = anno.getPathClass()?.toString()
                rowMap[ "Anno_ID" ]              = anno.getID()
                rowMap[ "Anno_ID_Path" ]         = idPath.reverse().join( "/" )
                rowMap[ "Anno_Area_um" ]         = anno.getROI()?.getScaledArea( pixelCalibration )
                rowMap[ "Anno_Hierarchy_Level" ] = anno.getLevel()
                rowMap[ "Parent_Name" ]          = parent?.getName()
                rowMap[ "Parent_Class" ]         = parent?.getPathClass()?.toString()
                // The root object is the image itself, not a real parent annotation, so it gets no ID
                rowMap[ "Parent_ID" ]            = parent?.isRootObject() ? null : parent?.getID()
                
                def MMs = anno.getMeasurementList()
                codePreferences.measurements.each { mmName ->
                    rowMap[ "Measurements_${mmName}" ] = MMs.containsKey( mmName ) ? MMs.get( mmName ) : null
                }
                
                def MD = anno.getMetadata()
                codePreferences.metadata.each { mdKey ->
                    rowMap[ "Metadata_${mdKey}" ] = MD.containsKey( mdKey ) ? MD.get( mdKey ) : null
                }
                
                localRows << rowMap
            }
            rowResults[ idx ] = localRows
        } finally {
            if (liveData == null) { imageData.close() } // a viewer's live data stays open for the viewer
        }
        
        progressUI.lastImageName.set( image.getImageName() )
        progressUI.doneCount.incrementAndGet()
    }) as Callable)
}

futures.each { it.get() }
pool.shutdown()

// Merge in order
emptyImages.addAll( emptyImagesSync )
rowResults.each { localRows -> if (localRows) completeMatrixMap.addAll( localRows ) }
headerRow = completeMatrixMap ? completeMatrixMap[0].keySet().toList() : []

if (stopLaterFlag.get()) { export = false }


// --- Final output matrix processing ---
// Remove columns where every row has null for that key
headerRow = headerRow.findAll { key ->
    completeMatrixMap.any { rowMap -> rowMap[ key ] != null }
}

// Then add the current image matrix rows
outputMatrix = completeMatrixMap.collect { rowMap ->
    headerRow.collect { key -> rowMap[ key ] }
}

// Add header
outputMatrix.addFirst( headerRow )
} catch (Throwable err) {
    // Runs outside the script editor, so a thrown error would be invisible: kill the pool, log it (the
    // full stack trace goes to QuPath's log), point the user there, and close the modal window so QuPath
    // isn't left blocked. Then stop.
    pool.shutdownNow()
    String failedOn = imageInFlight.get() ? " on image '${imageInFlight.get()}'" : ""
    logger.error( "Save data stopped after an error" + failedOn, err )
    Dialogs.showErrorNotification( "Save data", "Stopped after an error" + failedOn + ". See the log for details." )
    Platform.runLater { progressUI.stage.close() }
    return
}


// Closing progress bar window (runs on the success path)
Thread.sleep( 200 )
Platform.runLater {
    progressUI.stage.close()
}


// Firing conditional warnings if needed
if (emptyImages.size() == imagesToRun.size()) {
    Dialogs.showWarningNotification( "WARNING: No data exported", "No images contain viable annotations." )
    export = false
} else if (!emptyImages.isEmpty()) {
    Dialogs.showWarningNotification( "WARNING: Image(s) not included (no viable annotations)", " - " + emptyImages.join( "\n - ") )
}


// Exporting all data if allowed
if (export) {
    def file = new File( codePreferences.file )

    // A value holding a comma, quote or line break is quoted (with inner quotes doubled), so it stays
    // in one cell instead of spilling into the next columns. A missing value is left blank
    def csvCell = { v ->
        String text = v == null ? "" : "${v}"
        text ==~ /(?s).*[",\r\n].*/ ? '"' + text.replace( '"', '""' ) + '"' : text
    }
    
    // Catching any errors if the file is open
    exportAttemptLoop:
    while (true) {
        try {
            file.withWriter { writer ->
                outputMatrix.each {
                    def row = it.collect { csvCell( it ) }.join( "," )
                    writer.writeLine( row )
                }
            }
            
            Dialogs.showInfoNotification( "Saved successfully!", file.toString() )
            
            break exportAttemptLoop
        
        } catch (Exception e) {
            
            boolean retry = runOnFxThreadAndWait {
                def fileOpenAlert = new Alert( Alert.AlertType.CONFIRMATION )
                
                fileOpenAlert.setTitle( "Cannot write!" )
                fileOpenAlert.setHeaderText( "\"${file.getName()}\" is open in another application..." )
                fileOpenAlert.setContentText( "Close the file and try again." )
                
                def cancelBtn   = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE )
                def tryAgainBtn = new ButtonType( "Try again", ButtonBar.ButtonData.YES )
                fileOpenAlert.getDialogPane().buttonTypes.setAll( cancelBtn, tryAgainBtn )
                
                def result = fileOpenAlert.showAndWait().orElse( cancelBtn )
                return result == tryAgainBtn
            }
            
            if (!retry) { break exportAttemptLoop }
        }
    }
}


import java.nio.file.Files
import javafx.util.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.ActionEvent
import javafx.geometry.*
import javafx.scene.Scene
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.*
import javafx.scene.input.*
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.GsonBuilder
import com.google.gson.Gson
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 8
// ICON: SAVE
// Save custom annotation information to .csv