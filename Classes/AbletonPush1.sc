AbletonPush1 {
	var <>server, <midiOut, midiIn;
	var displayCache, padColorCache;
	var <buttonFuncs, <>padOnFunc, <>padOffFunc, <>padVelFunc, <>displayFunc, <>encoderFunc, <>ribbonFunc;
	var <>pedal1Func;

	*new {|server|
		^super.newCopyArgs(server).init()
	}

	*getProgressBar {|value| // between 0-1.0
		var steps = (value.clip(0, 1) * 32).trunc.asInteger;
		^Array.fill(8, { |i|
			var local = (steps - (i * 4)).clip(0, 4);
			switch(local,
				0, { 6 }, // --
				1, { 4 }, // -|
				2, { 3 }, // |-
				3, { 5 }, // ||
				4, { 5 }  // ||
			)
		})
	}

	*buttonCodes { ^[
		\play, 85,
		\rec, 86,
		\new, 87,
		\duplicate, 88,
		\automation, 89,
		\fixed_length, 90,
		\double, 117,
		\delete, 118,
		\undo, 119,
		\metronome, 9,
		\tap_tempo, 3,
		'1/4', 36,
		'1/4t', 37,
		'1/8',38,
		'1/8t', 39,
		'1/16',40,
		'1/16t',41,
		'1/32', 42,
		'1/32t', 43,
		\stop, 29,
		\master, 28,
		'arrow_down', 47,
		'arrow_up', 46,
		'arrow_right', 45,
		'arrow_left', 44,
		\select, 48,
		\shift, 49,
		\note, 50,
		\session, 51,
		\add_effect, 52,
		\add_track, 53,
		\octave_down, 54,
		\octave_up,55,
		\repeat, 56,
		\accent, 57,
		\scales, 58,
		\user, 59,
		\mute, 60,
		\solo, 61,
		\device, 110,
		\browse, 11,
		\track, 112,
		\clip, 113,
		\volume, 114,
		\pan_send, 115
	].asDict
	}

	*buttonKeys { ^this.buttonCodes.keys }
	*buttonCCs { ^this.buttonCodes.values }


	init {
		this.initMidiPort;
		midiOut.sysex(Int8Array[240,71,127,21,92,0,1,0,247]); // set note aftertouch
		midiOut.sysex(Int8Array[240,71,127,21,99,0,1,9 /*0-10*/ ,247]); // set ribbon to modwheel

		padColorCache = (0!3)!64;

		displayCache = 32!68!4; // 32=Char.space.ascii
		buttonFuncs = IdentityDictionary[];
		displayFunc = {};
		encoderFunc = {};
		padOnFunc = {};
		padOffFunc = {};
		padVelFunc = {};
		ribbonFunc = {};
		pedal1Func = {};

		this.makeMidiFuncs;
		this.clearDisplay;

	}

	initMidiPort {
				MIDIClient.init(); MIDIIn.connectAll;
		switch(thisProcess.platform.name)
		{\osx} {
			"mac os".postln;
			midiOut = MIDIOut.newByName("Ableton Push", "User Port");
			midiIn = MIDIIn.findPort("Ableton Push", "User Port");
		}
		{\linux } {
			"linux".postln;
			midiOut = MIDIOut.findPort("Ableton Push", "Ableton Push User Port");
			midiOut.latency_(0);
			midiIn = MIDIIn.findPort("Ableton Push", "Ableton Push User Port");
		};


	}

	makeMidiFuncs {

		MIDIFunc.cc({|val, cc| buttonFuncs[AbletonPush1.buttonCodes.invert[cc]].value},
			AbletonPush1.buttonCCs,0).permanent_(true);

		MIDIFunc.noteOn({|vel, note| padOnFunc.value(note-36, vel) }, (36..99),0).permanent_(true);
		MIDIFunc.noteOff({|vel, note| padOffFunc.value(note-36, vel) }, (36..99),0).permanent_(true);
		MIDIFunc.polytouch(\aftertouch, {|vel, note| padVelFunc.value(note-36, vel)}, (36..99), 0).permanent_(true);
		MIDIFunc.cc({|val| ribbonFunc !? { ribbonFunc.(val.linlin(0, 127,0, 1.0)) } }, 1,0).permanent_(true);
		MIDIFunc.cc({|val, num| encoderFunc.value(num-71, val)}, (71..79)).permanent_(true);
		MIDIFunc.cc({|val| pedal1Func.value(val) },69,0);

		Tdef(\updateDisplay, {
			{ this.updateDisplay; 0.5.wait }.loop
		}).play;

		CmdPeriod.add({ Tdef(\updateDisplay).play });
	}


	setPadColor {|padNum, r, g, b|
		var r1 = (r/16).round;
		var r2 = r % 16;
		var g1 = (g/16).round;
		var g2 = g % 16;
		var b1 = (b/16).round;
		var b2 = b % 16;
		if(padColorCache[padNum] != [r,g,b], {
			midiOut.sysex(Int8Array[240,71,127,21,4,0,8,padNum,0,r1, r2, g1, g2, b1, b2, 247]);
			padColorCache[padNum] = [r,g,b]
		});
	}

	clearPads {
		64.do { |i| this.setPadColor(i, 0, 0, 0) }
	}



	// display / encoders
	writeString {|row, block, string|
		var offset = #[0,9,17,26,34,43,51,60][block];
		var ascii = string.ascii;

		// update single chars
		ascii.do{|char, indx|
			indx = indx+offset;
			if(displayCache[row][indx]!=char, {
				midiOut.sysex(Int8Array.newFrom([240,71,127,21,24+row,0,1+1,indx,char,247]));
				displayCache[row][indx] = char;
			});
		}

		//update whole string
		/*if(displayCache[row][offset..(offset+string.size-1)] != ascii, {
		midiOut.sysex(
		Int8Array.newFrom([240,71,127,21,24+row,0,ascii.size+1,offset,ascii,247].flatten)
		);
		ascii.do{|char, indx| displayCache[row][indx+offset] = char };
		});*/

	}

	writeAscii {|row, block, ascii|
		var offset = #[0,9,17,26,34,43,51,60][block];
		if(displayCache[row][offset..(offset+ascii.size-1)] != ascii) {
			midiOut.sysex( Int8Array.newFrom([240,71,127,21,24+row,0,ascii.size+1,offset,ascii,247].flatten) );
			ascii.do{|char, indx| displayCache[row][indx+offset] = char };
		};
	}

	clearLine { |line| midiOut.sysex(Int8Array[240,71,127,21,28+line,0,0,247]) }

	clearDisplay{ 4.do{ |l| this.clearLine(l) } }

	clearBlock{ |row, block| this.writeAscii(row, block, 32!8) }


}