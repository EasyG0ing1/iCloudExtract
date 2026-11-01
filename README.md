# iCloudExtract

#### ** Current version 1.2.1 **

Extracts the multi-part zip files you get from Apple's *Data and Privacy* export
(`iCloud Drive Part 1 of 18.zip`, `Part 2 of 18`, ...) without losing files.

The built-in tools (`unzip`, `ditto`, Archive Utility) and several third-party ones either stop at the
first problem or silently drop entries on these archives. iCloudExtract handles each entry on its own,
logs every problem, and can verify that everything made it out.

## What goes wrong with these archives

* **A path that is both a file and a folder.** Apple's zips can hold (for example) `Untitled.numbers` as a real file
  (itself a zip: iWork's single-file format) *and* entries such as `Untitled.numbers/preview.jpg`.
  A filesystem cannot hold both under one name. The usual unzip tools don't handle these cases intelligently.
* **macOS `unzip` stops on some perfectly valid names**, such as folders containing a curly apostrophe
  (`Dan’s Pro Drive`), with `Illegal byte sequence`. It then skips those files and, after a
  write error, can abandon the rest of the archive. (In the archives this was written against, every
  name is valid UTF-8; the problem is `unzip`, not the names.)
* **Two entries whose names collide** once extracted: the same name listed twice, or names that differ
  only by letter case, which a macOS or Windows volume treats as one file.
* **The zip stores no file attributes at all** (no modes, no directory flags). Folders are only implied
  by the paths.

## Best Use

The best way to use this program is to get all of the partial zip files downloaded into a single folder where the zip
files will have numeric sequencing (1 of 10, 2 of 10) in the zip file name. You don't need every zip file but its best
to have them all.

Then, from inside that folder, run 

```bash
icloudextract -consolidate -destination <path>
```

The other command line options are explained below

Make sure the destination folder is a clean folder with nothing in it. However, if you are incrementally extracting zip files
as they are downloaded you should run the program like this:

```bash
icloudextract -consolidate -source filename.zip -destination <path>
```

Supply the same destination folder, while providing just the new zip file and the program will simply merge those files into
the common path. 

## Installation

Simply [download the binary](https://github.com/EasyG0ing1/iCloudExtract/releases) for your operating system / architecture,
then unzip the file and place the executable file into a folder that is in your environment PATH variable. 

If you need information on modifying your PATH variable, [Click here](https://www.google.com/search?q=how+to+modify+the+PATH+environment+variable).


## For each run

Regardless of how you chose to extract your files, the program will always account for each file in the zip archives and will
log any changes that it needed to make in order to get the files unzipped and it will tell you exactly which files were
affected.

This program was written after attempting to obtain my entire iCloud drive in zip files then having a nightmare of a time
trying to get them all extracted as thoroughly as possible. Apples own built in tools would silently ignore files and they
would not get extracted and even programs like BetterZip had similar behaviors. I did not find that to be acceptable, so 
I wrote this utility.

## Program Arguments

```
icloudextract [-destination <folder>] [-source <folder|zip>] [-verify [crc]] [-consolidate]
              [-overwrite [newest]] [-checkzip] [-threads <n>] [-getTotal] [-yes]
```

| Option | Meaning |
|---|---|
| `-destination <folder>` | Where to extract (created if needed). <br>Default: a folder named `iCloudExtract` inside the current folder. |
| `-source <folder\|zip>` | A folder OR a zip file. If specifying a folder, it will automatically grab the zip files in that folder. <BR> Default: Current folder. |
| `-yes` | This tells the program to automaticall respond YES to the confirmation alerts if they are needed. |
| `-verify` | After files have been unzipped, this verifies the **size** of the extracted file with the size recorded in the zip file. |
| `-verify crc` | Same behavior as -verify except the files computed **CRC-32** value is used as confirmation - this is much more accurate but it takes more time to complete. It also changes how files that are already on disk are matched: instead of name and size alone, their CRC-32 is compared too (each existing file has to be read). |
| `-consolidate` | Each Zip file contains a folder path that is common to all of the zip files. Without this option, each zip file would be unzipped into its own separate folder. With this option, the files are extracted into the folder that is common in all zip files. |
| `-overwrite` | If a file is being moved or unzipped and a file already exists at that path, this option overwrites the destination file with the source file. |
| `-overwrite newest` | Same as -overwrite, but it only overwrites the destination file when the source file is newer. |
| `-checkzip` | This will run a validation check on the zip file before extracting it and if the check fails, the files will not be extracted from that zip file. |
| `-threads <n>` | Unzip more than one zip file simultaneously. This is especially effective when you are unzipping to a solid state hard drive (see discussion below). |
| `-getTotal` | Shows how much disk space the extraction needs, without extracting anything or writing any files. It reads only the table of contents of each zip, so it is fast, and it leaves out everything that would never be written: identical copies of a file that an earlier zip already provides, files that lose to a different file at the same path, and files replaced by a later version. It follows `-source`, `-consolidate` and `-overwrite`, and assumes an empty destination and the zips extracted one after the other. The result is shown like `83.42 GB`, with the exact number of bytes beside it. |
| `-help`, `-version` | View the programs help text or the version of the program. |

Every option also works with two dashes (`--destination`).

## About Program Arguments

If you just run the program from a folder that has the zip files in it, the program will extract each zip file into its own separate folder. If you use any of the arguments, this behavior is worth noteng:

`-source` 
  - can be a single zip file or a path to a folder that has one or more of your downloaded zip files

`-yes` 
  - This option is meant for use in a script file so that nothing stops the program from running

`-consolidate` 
  - This option can do different things depending on the state of your overall file extraction:
  - You can start extracting zip files individually as they are downloaded using the consolidate argument. You just need to make sure that the `-destination` path is the same each time you run the program for the different zip files. It will consolidate and resolve conflicts based on the default rules or based on the arguments you use that can affect how the program resolves conflicts (such as the `-overwrite` option).
  - If you have multiple zip files already extracted into individual folders, then using `-consolidate` will look in the destination folder and it will resolve the path that is common to each individual folder and then it will simply create the common path and it will move the files from each folder into the common path, creating the consolidated folder. It follows all of the same rules for conflict resolution as it would if it were unzipping files.
  - When files have already been written to disk at the time you run `iCloudExtract`, and a source file is the exact same file that is already in the destination, it will skip that file and NOT log it.

`-overwrite` 
  - when this option is NOT used, files having the same name and size will be compared by CRC value and when they are identical, no overwrite will be done. However, if there is a descrepency, then the program will take action and log what it did.
  - Using this argument will change that behavior where it will always overwrite the destination file with the source file regardless of CRC checks. And by default it will extract each zip file in sequence so by default the next zip file containing a matching destination file will be overwritten with the file in that next sequential zip file.
  - Using the `-threads` argument offers no way to predict which source file will overwrite an existing destination file because sequence is generally ignored when you use that argument.
  - Using `-overwrite newest` will only overwrite the file when the source file is newer.

`-threads`
  - This option is incredibly useful when you are extracting files onto a solid state drive (nVme etc.), because it launches new threads for each zip file and extracts them simultaneously. And because solid state drives have no moving parts, this can make the overall extraction process incredibly fast.
  - Using this option when the files are being extracted to a bare metal hard drive will most likely slow down the overall process. I dont recommend using this option when you are writing to a bare metal hard drive.
  - Files are unzipped in sequence up to the number of threads you specify. For example, if you have 10 zip files but you use `-threads 5`, zip files 1 through 5 will be extracted immediately then as file extraction finishes, the next zip file in the sequence will be added to the thread pool.

### Progress display

While it runs, the program prints a line for each step: `Unzipping file <name>.zip` (with `into <destination>` when
`-consolidate` is on), and `Moving files for consolidation: ...` while zip-named folders are being moved up. On
a terminal, a spinning line (`/ - \ |`) sits below them with the running time as `HH:MM:SS` and what is being done
right now; it carries on as each next zip starts. When everything is done it prints a summary, the elapsed time and
where the log files are. If the output is redirected to a file, only the plain lines are printed (no spinner).

### Examples

```
# Extract every zip in the current folder into ./iCloudExtract, one folder per zip
icloudextract

# Merge all parts into one tree and check every CRC afterwards
icloudextract -source ~/Downloads/iCloudFiles -destination ~/Downloads/iCloudFiles/Extracted -consolidate -verify crc

# Four zips at once, newest copy wins, test the zips first
icloudextract -source ~/Downloads -destination ~/iCloudDrive -consolidate -overwrite newest -threads 4 -checkzip
```

## Logs

Written to the folder you run the program from:

* `iCloudExtract.log`: everything unusual, including every per-entry error.
* `iCloudExtract-conflicts.log`: files skipped (different, or identical to a copy written earlier in the same run), overwritten, renamed, and folders
  redirected to `-folder`.

The exit code is `0` when everything succeeded, `1` when any entry failed or verification found a
mismatch, and `2` for bad arguments.

## Building

Needs a GraalVM JDK 21+ and Maven.

```
mvn package                 # runnable jar: java -jar target/icloudextract.jar ...
mvn -Pnative package        # native executable: target/icloudextract  (needs GraalVM with native-image)
```

A GitHub Actions workflow (`.github/workflows/build.yml`) builds native executables for Linux, macOS
(Intel and Apple Silicon) and Windows.

## Notes and limits

* Windows: characters Windows forbids in file names are written as `%HH`, and reserved names such as
  `CON` get a leading `_`.
* Whether two names "collide" is decided by the file system itself, so on a case-sensitive volume
  (the Linux default) names that differ only by case simply stay separate files.
* A file already on disk that this run did not write is considered identical when its name and size match;
  no CRC-32 is computed, whether the file is being extracted over or moved up with `-consolidate` (an
  identical source file in a zip-named folder is deleted silently; one of a different size follows `-overwrite`,
  or `-N` renaming when the names differ only by case). The CRC-32 is only used on files this run wrote itself,
  and by the `-verify crc` and `-checkzip` options you ask for. Reading a file for its CRC-32 is limited by the disk, not the CPU.
  With `-verify crc`, the match test for files already on disk is extended to include the CRC-32 as well.
* Entry timestamps are applied to the extracted files. File permissions are not (the zips carry none).
* Encrypted entries and unsupported compression methods are reported as failures; the rest still extract.


## Assistance

If you are having any issues using this utility or you find something that this tool is lacking and would like to suggest any changes, open up an issue.
